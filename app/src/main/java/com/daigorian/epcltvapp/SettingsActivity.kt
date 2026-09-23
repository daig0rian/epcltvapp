package com.daigorian.epcltvapp

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/**
 * 設定画面。
 *
 * 中身の [SettingsFragment] は leanback の platform fragment 版なので、下の
 * `fragmentManager`（非推奨）のまま載せている。一方で「アップデートを確認」が出す
 * [AppUpdateDialogFragment] は androidx の DialogFragment なので `supportFragmentManager`
 * が要る。両方を満たすために [FragmentActivity] を継承する（テーマの親が Theme.Leanback で
 * AppCompat ではないため AppCompatActivity は使えない）。
 */
class SettingsActivity : FragmentActivity() {

    companion object {
        const val EXTRA_START_SCREEN = "start_screen"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O) {
            requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE
        }

        if (savedInstanceState == null) {
            val fragment = SettingsFragment()
            val startScreen = intent.getStringExtra(EXTRA_START_SCREEN)
            if (startScreen != null) {
                fragment.arguments = Bundle().apply {
                    putString(SettingsFragment.ARG_START_SCREEN, startScreen)
                }
            }
            @Suppress("DEPRECATION")
            fragmentManager.beginTransaction()
                .replace(R.id.settings_container, fragment)
                .commit()
        }
    }
}
