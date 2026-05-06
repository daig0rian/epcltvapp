package com.daigorian.epcltvapp

import android.app.Activity
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.os.Build
import android.os.Bundle

class SettingsActivity : Activity() {

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
