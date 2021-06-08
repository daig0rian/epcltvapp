package com.daigorian.epcltvapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.preference.LeanbackPreferenceFragment
import androidx.preference.*


class SettingsFragment : LeanbackPreferenceFragment()  {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        //IPアドレスの入力時バリデーション
        val ipAddrPref = getPreferenceScreen().findPreference(getText(R.string.pref_key_ip_addr)) as EditTextPreference?
        ipAddrPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, value ->
            val regex = Regex(pattern ="""^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$""")
            if (value.toString().matches(regex)){
                true
            }else{
                Toast.makeText(context!!, getString(R.string.not_a_valid_ipv4_addr,value.toString()), Toast.LENGTH_LONG).show()
                false
            }

        }

        //PORT番号の入力時バリデーション
        val portNumPref = getPreferenceScreen().findPreference(getText(R.string.pref_key_port_num)) as EditTextPreference?
        portNumPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, value ->
            val regex = Regex(pattern ="""^([0-9]|[1-9][0-9]{1,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])$""")
            if (value.toString().matches(regex)){
                true
            }else{
                Toast.makeText(context!!, getString(R.string.not_a_valid_port_num,value.toString()), Toast.LENGTH_LONG).show()
                false
            }

        }

    }


    companion object{
        fun isPreferenceAllExists(context: Context):Boolean{
            val pref = PreferenceManager.getDefaultSharedPreferences(context)
            return (
                    pref.contains(context.getString(R.string.pref_key_ip_addr)) &&
                    pref.contains(context.getString(R.string.pref_key_port_num)) &&
                    pref.contains(context.getString(R.string.pref_key_fetch_limit)) &&
                    pref.contains(context.getString(R.string.pref_key_player))
                    )
        }
    }

}
