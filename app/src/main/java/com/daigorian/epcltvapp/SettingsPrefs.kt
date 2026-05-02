package com.daigorian.epcltvapp

import android.content.Context
import androidx.preference.PreferenceManager

object SettingsPrefs {

    const val IP_REGEX_PATTERN =
        """^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$"""
    const val PORT_REGEX_PATTERN =
        """^([1-9]|[1-9][0-9]{1,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])$"""

    fun isPreferenceAllExists(context: Context): Boolean {
        val pref = PreferenceManager.getDefaultSharedPreferences(context)
        val ipString = pref.getString(context.getString(R.string.pref_key_ip_addr), "")
        if (ipString?.matches(Regex(IP_REGEX_PATTERN)) != true) return false
        val portString = pref.getString(context.getString(R.string.pref_key_port_num), "")
        if (portString?.matches(Regex(PORT_REGEX_PATTERN)) != true) return false
        return pref.contains(context.getString(R.string.pref_key_player)) &&
                pref.contains(context.getString(R.string.pref_key_num_of_history)) &&
                pref.contains(context.getString(R.string.pref_key_use_custom_base_url))
    }
}
