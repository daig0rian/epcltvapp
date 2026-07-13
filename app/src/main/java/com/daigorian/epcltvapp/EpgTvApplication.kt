package com.daigorian.epcltvapp

import android.app.Application
import android.util.Log
import java.io.File

/**
 * USBデバッグが使えない環境でもクラッシュ内容を確認できるよう、
 * 未捕捉例外のスタックトレースを内部ストレージに保存しておく。
 * 次回起動時に MainFragment がこのファイルを読んで表示する。
 */
class EpgTvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                File(filesDir, CRASH_LOG_FILENAME).writeText(Log.getStackTraceString(throwable))
            } catch (_: Exception) {
                // 保存自体に失敗しても元のクラッシュ処理は継続させる
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_LOG_FILENAME = "last_crash.txt"
    }
}
