package com.daigorian.epcltvapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * リリースに添付された APK をダウンロードし、システムのインストーラーへ渡す。
 *
 * **完全サイレントなインストールは狙っていない。** 無確認での更新は Android 12 以降の
 * `UPDATE_PACKAGES_WITHOUT_USER_ACTION` が要り、しかも「自分が installer of record である
 * 既存アプリの更新」が条件なので初回は必ず確認が出る。Fire OS 7/8 (Android 9/11 相当) には
 * そもそもこの仕組みが無い。よってここでの到達点は「システムのインストール確認画面まで
 * 利用者を運ぶ」ことまでとする。
 *
 * 進捗と結果は必ずメインスレッドで通知する。呼び出し側 (ダイアログ) が破棄されたら
 * [cancel] を呼ぶこと。以後の通知は行わない。
 */
class AppUpdateDownloader(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var call: okhttp3.Call? = null
    private var listener: Listener? = null

    interface Listener {
        fun onProgress(percent: Int)
        fun onCompleted(file: File)
        /** [messageResId] は利用者に見せる文言。原因の詳細はログにだけ出す。 */
        fun onFailed(messageResId: Int)
    }

    /**
     * [url] の APK を保存領域に落とす。進行中のダウンロードがあれば破棄して置き換える。
     *
     * ダウンロード先は毎回まっさらにする。確認結果を持ち越さない設計なので、
     * 前回の残骸を再利用する余地を作らない (古い APK が溜まり続けるのも防ぐ)。
     */
    fun start(url: String, expectedSize: Long?, listener: Listener) {
        cancel()
        this.listener = listener

        val destination = prepareDestination()
        if (destination == null) {
            notifyFailed(R.string.app_update_error_no_storage)
            return
        }

        val newCall = client.newCall(Request.Builder().url(url).build())
        call = newCall
        newCall.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (call.isCanceled()) return
                Log.w(TAG, "download failed", e)
                notifyFailed(R.string.app_update_error_download)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        Log.w(TAG, "download failed: HTTP ${it.code}")
                        notifyFailed(R.string.app_update_error_download)
                        return
                    }
                    val body = it.body
                    if (body == null) {
                        Log.w(TAG, "download failed: empty body")
                        notifyFailed(R.string.app_update_error_download)
                        return
                    }
                    // Content-Length が無い場合に備え、リリース情報のサイズを分母に使う。
                    val total = body.contentLength().takeIf { len -> len > 0 }
                        ?: expectedSize?.takeIf { size -> size > 0 }
                        ?: -1L
                    try {
                        writeToFile(body.byteStream(), destination, total, call)
                    } catch (e: IOException) {
                        if (call.isCanceled()) return
                        Log.w(TAG, "download failed while writing", e)
                        destination.delete()
                        notifyFailed(R.string.app_update_error_download)
                        return
                    }
                    if (call.isCanceled()) {
                        destination.delete()
                        return
                    }
                    mainHandler.post { this@AppUpdateDownloader.listener?.onCompleted(destination) }
                }
            }
        })
    }

    /** 進行中のダウンロードを止め、以後の通知を打ち切る。 */
    fun cancel() {
        call?.cancel()
        call = null
        listener = null
    }

    @Throws(IOException::class)
    private fun writeToFile(input: java.io.InputStream, destination: File, total: Long, call: okhttp3.Call) {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L
        var lastPercent = -1
        input.use { source ->
            destination.outputStream().use { sink ->
                while (true) {
                    if (call.isCanceled()) return
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    if (total > 0) {
                        val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                        // 1%刻みでしか通知しない。TVの描画を無駄に起こさないため。
                        if (percent != lastPercent) {
                            lastPercent = percent
                            mainHandler.post { listener?.onProgress(percent) }
                        }
                    }
                }
            }
        }
    }

    /** 保存先のディレクトリを空にして、書き込むファイルを返す。使える領域が無ければ null。 */
    private fun prepareDestination(): File? {
        val base = appContext.externalCacheDir ?: appContext.cacheDir ?: return null
        val dir = File(base, APK_DIR_NAME)
        dir.listFiles()?.forEach { it.delete() }
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, APK_FILE_NAME)
    }

    private fun notifyFailed(messageResId: Int) {
        mainHandler.post { listener?.onFailed(messageResId) }
    }

    companion object {
        private const val TAG = "AppUpdateDownloader"
        private const val APK_DIR_NAME = "apk"
        private const val APK_FILE_NAME = "update.apk"
        private const val BUFFER_BYTES = 64 * 1024

        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // 本体のダウンロードなので読み取りは長めに取る。
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * 「不明なアプリのインストール」が許可されているか。
         *
         * API 26 未満にはアプリ単位の許可が無く、端末全体の「提供元不明のアプリ」設定に従う。
         * アプリからは判定も誘導もできないので、許可済みとみなしてそのまま Intent を投げ、
         * 失敗はシステム側の表示に任せる。
         */
        fun canInstall(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
            return context.packageManager.canRequestPackageInstalls()
        }

        /**
         * ダウンロード済み APK をシステムのインストーラーに渡す Intent。
         *
         * API 24 未満は `content://` を解釈できないインストーラーがあるため `file://` を渡す
         * (だから保存先は外部キャッシュにしてある)。
         */
        fun installIntent(context: Context, apk: File): Intent {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            } else {
                Uri.fromFile(apk)
            }
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        /** 「不明なアプリのインストール」の許可画面へ飛ぶ Intent (API 26 以降)。 */
        fun unknownSourcesSettingsIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
    }
}
