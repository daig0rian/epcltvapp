package com.daigorian.epcltvapp

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.preference.LeanbackPreferenceFragment
import androidx.preference.*


class SettingsFragment : LeanbackPreferenceFragment()  {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        //IPアドレスの入力時バリデーション
        val ipAddressPref = preferenceScreen.findPreference(getText(R.string.pref_key_ip_addr)) as EditTextPreference?
        ipAddressPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
            val regex = Regex(pattern =IP_REGEX_PATTERN)
            if (value.toString().matches(regex)){
                true
            }else{
                Toast.makeText(activity, getString(R.string.not_a_valid_ipv4_addr,value.toString()), Toast.LENGTH_LONG).show()
                false
            }

        }

        //PORT番号の入力時バリデーション
        val portNumPref = preferenceScreen.findPreference(getText(R.string.pref_key_port_num)) as EditTextPreference?
        portNumPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
            val regex = Regex(pattern =PORT_REGEX_PATTERN)
            if (value.toString().matches(regex)){
                true
            }else{
                Toast.makeText(activity, getString(R.string.not_a_valid_port_num,value.toString()), Toast.LENGTH_LONG).show()
                false
            }

        }

    }


    companion object{
        private const val IP_REGEX_PATTERN = """^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$"""
        private const val PORT_REGEX_PATTERN = """^([1-9]|[1-9][0-9]{1,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])$"""

        fun isPreferenceAllExists(context: Context):Boolean{
            val pref = PreferenceManager.getDefaultSharedPreferences(context)
            //IPアドレスの妥当性チェック
            val ipRegEx = Regex(pattern =IP_REGEX_PATTERN)
            val ipString = pref.getString(context.getString(R.string.pref_key_ip_addr),"")
            if (ipString?.matches(ipRegEx) != true){
                return false
            }
            //ポート番号の妥当性チェック
            val portRegEx = Regex(pattern =PORT_REGEX_PATTERN)
            val portString = pref.getString(context.getString(R.string.pref_key_port_num),"")
            if (portString?.matches(portRegEx) != true){
                return false
            }
            //ほかのパラメータの存在チェック
            return (
                pref.contains(context.getString(R.string.pref_key_fetch_limit)) &&
                pref.contains(context.getString(R.string.pref_key_player)) &&
                pref.contains(context.getString(R.string.pref_key_num_of_history))
            )
        }
    }

}
