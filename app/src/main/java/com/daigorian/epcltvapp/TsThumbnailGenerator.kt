package com.daigorian.epcltvapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.leanback.widget.PlaybackSeekDataProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import okhttp3.OkHttpClient

private const val TAG = "TsThumbnailGenerator"

/**
 * Leanbackの制約上、シークステップとgetThumbnail()呼び出しは1:1にせざるを得ない。
 * しかし1枚の生成コストは実測で500ms〜1秒程度あり、これを全シークステップ分実行するのは
 * 非現実的。そこで[REAL_THUMBNAIL_STRIDE]点に1点だけ実際に生成し、残りは即座に
 * [placeholderBitmap]を返す。同じサムネイルを複数ステップに使い回す案は「未取得なのか
 * 同じ絵柄なのか区別がつかない」というUXフィードバックで不採用になった。続けて試した
 * 「NO IMAGE」画像もUX上好ましくなかったため、完全透過画像を採用した(実機確認で良好)。
 */
private const val REAL_THUMBNAIL_STRIDE = 4

/** プレースホルダーの表示サイズ。正方形だと実サムネイルの列の中で浮いて見えるため、
 *  Leanbackのシークバー上での見た目を確認した上で2:1に調整した。 */
private const val PLACEHOLDER_WIDTH = 320
private const val PLACEHOLDER_HEIGHT = 160

/**
 * TSシーク点の一部にサムネイルを付与する。[androidx.media3.inspector.frame.FrameExtractor]
 * (ExoPlayer自身のTsExtractorを使ってフレームをデコードするAPI)を使う。
 *
 * 実機検証により、FrameExtractorが内部で使うExoPlayerの`seekTo()`(既に開いたストリーム内での
 * シーク)はこのアプリのARIB TSに対して機能しないことが判明した(要求位置に関わらず常に
 * 準備直後の最初のフレームを返す——mediaのソースコード上のコメント
 * 「If the seek resolves to the current position, ... No frames are rendered. Repeat the
 * previously returned frame.」と一致する挙動)。
 *
 * そのため`seekTo()`には一切頼らず、Phase 1のシーク機構(TsSeekDataProvider.estimateByteOffset
 * + TsReadexDataSource.startByteOffset)を流用する。シーク点ごとに、その概算バイト位置から
 * 開始するMediaSourceを都度新規に構築し(FrameExtractor 1.10.0+のBuilder.setMediaSourceFactory
 * で注入)、「そのストリームの先頭付近のフレーム」を取得する([FrameExtractor.getThumbnail]、
 * 常にpositionMs=0相当を要求するため、機能しないseekTo()経路を通らない)。
 *
 * 生成は[getThumbnail]が実際に呼ばれた時点でのオンデマンドとする。Leanbackは表示中の
 * シークステップ全部に対してgetThumbnail()を呼び、コールバックが来ていない間は直前に
 * 届いたBitmapを表示し続ける(=間のステップは自動的に埋まる)ため、こちらで事前生成
 * スケジュールを組む必要はない。[PlaybackSeekDataProvider.getThumbnail]は
 * 「UIスレッドから呼ばれる」契約なので、FrameExtractorのスレッド固定要件もこれで自然に
 * 満たされる(呼び出し元を別途mainスレッドに揃える必要がない)。
 *
 * 生成は要求された生インデックスごとに行う(隣接点をバケットにまとめて使い回す案は、
 * 「未取得なのか同じ絵柄なのか区別がつかずグリッチに見える」というUXフィードバックにより
 * 不採用)。
 *
 * 【重要】media3 1.10.1のFrameExtractorInternalソース確認により判明: `needsNewPlayer`判定に
 * `request.mediaSourceFactory != currentMediaSourceFactory`という「参照の比較」が含まれる。
 * リクエストのたびに新しい[MediaSource.Factory]インスタンスを作ると、その都度ExoPlayer
 * インスタンス自体が一から作り直される(MediaSourceの再準備どころではない重いコスト)。
 * そのため[tsFactory]/[mediaSourceFactory]は使い回し、`startByteOffset`はその可変プロパティを
 * 書き換えることでシーク点ごとの違いを表現する。書き換えと実際の`open()`呼び出しの間で
 * 競合しないよう、生成リクエストは[queue]で1件ずつ順番に処理する。
 */
@UnstableApi
internal class TsThumbnailGenerator(
    private val context: Context,
    private val videoUrl: String,
    httpClient: OkHttpClient,
    private val positionsMs: LongArray,
    private val estimateByteOffset: (relativeTimeMs: Long) -> Long,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    // ネイティブtsreadex処理(ARIB字幕等)はサムネイル用途では不要なため無効化し、
    // 単純なバイト位置オフセットの素通しだけを行う。ExoPlayer自体の使い回しのため
    // インスタンスは1つだけ保持し、startByteOffsetをリクエストごとに書き換える。
    // [調査用] maxReadLengthでLoadControlデフォルトの先読みバッファ目標より小さい
    // 上限を強制し、早期EOFで1フレーム取得が速くなるかを検証する。
    private val tsFactory = TsReadexDataSource.Factory(OkHttpDataSource.Factory(httpClient)).apply {
        nativeProcessingEnabled = false
        maxReadLength = 6L * 1024 * 1024
    }
    private val mediaSourceFactory = ProgressiveMediaSource.Factory(tsFactory)

    /**
     * [調査用] 完全透過のプレースホルダーで、Leanbackのシークバー上で「何も描かれない」
     * 見た目になるかを確認する(NO IMAGE画像はUX上好ましくなかったため)。
     */
    private val placeholderBitmap: Bitmap = Bitmap.createBitmap(
        PLACEHOLDER_WIDTH, PLACEHOLDER_HEIGHT, Bitmap.Config.ARGB_8888
    ).apply {
        eraseColor(Color.TRANSPARENT)
    }

    private val cache = HashMap<Int, Bitmap>()
    private val pendingCallbacks = HashMap<Int, PlaybackSeekDataProvider.ResultCallback>()
    private val queue = ArrayDeque<Int>()
    private val queued = HashSet<Int>()
    private var generating = false
    private val openExtractors = ArrayList<FrameExtractor>()
    private var released = false

    /** [PlaybackSeekDataProvider.getThumbnail]からの委譲先。[REAL_THUMBNAIL_STRIDE]点に1点だけ実際に生成する。 */
    fun getThumbnail(index: Int, callback: PlaybackSeekDataProvider.ResultCallback) {
        if (index % REAL_THUMBNAIL_STRIDE != 0) {
            callback.onThumbnailLoaded(placeholderBitmap, index)
            return
        }
        val cached = cache[index]
        if (cached != null) {
            callback.onThumbnailLoaded(cached, index)
            return
        }
        pendingCallbacks[index] = callback
        if (queued.add(index)) {
            queue.addLast(index)
            processQueueIfIdle()
        }
    }

    /** [PlaybackSeekDataProvider.reset]からの委譲先。進行中の生成自体はキャッシュに残るため継続させる。 */
    fun reset() {
        pendingCallbacks.clear()
    }

    /** フラグメント破棄時に呼び、FrameExtractorのネイティブリソースを解放する。 */
    fun release() {
        released = true
        queue.clear()
        queued.clear()
        openExtractors.forEach { it.close() }
        openExtractors.clear()
    }

    private fun processQueueIfIdle() {
        if (generating || released) return
        // queuedからはgenerate()の完了時に取り除く(ここで取り除くと、生成中に同じindexへの
        // getThumbnail()が来た際に「未キュー」と誤判定し重複生成してしまうため)。
        val index = queue.removeFirstOrNull() ?: return
        generating = true
        generate(index)
    }

    private fun generate(index: Int) {
        if (released || index !in positionsMs.indices) {
            queued.remove(index)
            generating = false
            processQueueIfIdle()
            return
        }
        // 使い回すmediaSourceFactoryに次の位置をセットしてから、直後にgetThumbnail()するまでの
        // 間に別のリクエストが割り込まないよう、キューによる直列化で保証している。
        tsFactory.startByteOffset = estimateByteOffset(positionsMs[index])
        // mediaIdを点ごとに変えることで、FrameExtractor内部の「同一MediaItemなら再利用」
        // 判定(needsPrepare)を確実に不成立にし、都度新しいMediaSource(=新しいバイト位置)で
        // 再準備させる。mediaSourceFactory自体は使い回すため、ExoPlayerインスタンスは
        // 再生成されない。
        val mediaItem = MediaItem.Builder().setMediaId("thumb-$index").setUri(videoUrl).build()
        val extractor = FrameExtractor.Builder(context, mediaItem)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        openExtractors.add(extractor)

        val startedAtMs = SystemClock.elapsedRealtime()
        val future = extractor.getThumbnail()
        Futures.addCallback(
            future,
            object : FutureCallback<FrameExtractor.Frame> {
                override fun onSuccess(result: FrameExtractor.Frame?) {
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                    Log.d(TAG, "[調査] index=$index elapsedMs=$elapsedMs 成功")
                    if (result != null) {
                        cache[index] = result.bitmap
                        pendingCallbacks.remove(index)?.onThumbnailLoaded(result.bitmap, index)
                    }
                    queued.remove(index)
                    generating = false
                    processQueueIfIdle()
                }

                override fun onFailure(t: Throwable) {
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                    Log.w(TAG, "[調査] index=$index elapsedMs=$elapsedMs 失敗", t)
                    queued.remove(index)
                    generating = false
                    processQueueIfIdle()
                }
            },
            mainExecutor,
        )
    }
}
