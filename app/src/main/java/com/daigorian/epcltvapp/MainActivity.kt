package com.daigorian.epcltvapp

import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity

/**
 * Loads [MainFragment].
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, MainFragment())
                .commitNow()
        }
    }

    /**
     * キー・タッチが届いたことを [MainFragment] へ伝える。
     *
     * 画面が作り直されたあと、選んでいた行が現れるのを待って選び直す処理が動いている。その間に
     * 利用者が自分で動かしたら、こちらの復元はやめる（フォーカスを奪わない）。
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        notifyUserInteraction()
    }

    /**
     * リモコンのキーは [onUserInteraction] では拾えないので、こちらでも伝える。
     * フォーカス移動に使う D-pad はグリッドが消費してしまうため、届いた時点で知らせる必要がある。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        notifyUserInteraction()
        return super.dispatchKeyEvent(event)
    }

    private fun notifyUserInteraction() {
        (supportFragmentManager.findFragmentById(R.id.main_browse_fragment) as? MainFragment)
            ?.onUserInteractionByUser()
    }
}
