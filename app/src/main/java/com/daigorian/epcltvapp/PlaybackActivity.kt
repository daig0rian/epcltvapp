package com.daigorian.epcltvapp

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity

/** Loads [PlaybackVideoFragment]. */
class PlaybackActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 再生中に無操作でスクリーンセーバーへ入るのを防ぐ
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, PlaybackVideoFragment())
                .commit()
        }
    }
}