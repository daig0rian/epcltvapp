package com.daigorian.epcltvapp

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.preference.LeanbackPreferenceFragment
import androidx.leanback.preference.LeanbackSettingsFragment
import androidx.preference.*
import androidx.preference.DialogPreference.TargetFragment


class SettingsFragment : LeanbackSettingsFragment(), TargetFragment {
    private var mPreferenceFragment: PreferenceFragment? = null
    override fun onPreferenceStartInitialScreen() {
        mPreferenceFragment = buildPreferenceFragment(R.xml.preferences, null)
        startPreferenceFragment(mPreferenceFragment!!)
    }

    override fun onPreferenceStartFragment(
        preferenceFragment: PreferenceFragment,
        preference: Preference
    ): Boolean {
        return false
    }

    override fun onPreferenceStartScreen(
        preferenceFragment: PreferenceFragment,
        preferenceScreen: PreferenceScreen
    ): Boolean {
        val frag = buildPreferenceFragment(
            R.xml.preferences,
            preferenceScreen.key
        )
        startPreferenceFragment(frag)
        return true
    }


    private fun buildPreferenceFragment(preferenceResId: Int, root: String?): PreferenceFragment {
        val fragment: PreferenceFragment =
            PrefFragment()
        val args = Bundle()
        args.putInt(
            PREFERENCE_RESOURCE_ID,
            preferenceResId
        )
        args.putString(
            PREFERENCE_ROOT,
            root
        )
        fragment.arguments = args
        return fragment
    }

    override fun <T : Preference?> findPreference(key: CharSequence): T? {
        return mPreferenceFragment!!.findPreference(key)
    }

    class PrefFragment : LeanbackPreferenceFragment()  {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val root = arguments.getString(PREFERENCE_ROOT, null)
            val prefResId = arguments.getInt(PREFERENCE_RESOURCE_ID)
            if (root == null) {
                addPreferencesFromResource(prefResId)
            } else {
                setPreferencesFromResource(prefResId, root)
            }


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
        }

    }


    companion object {
        private const val PREFERENCE_RESOURCE_ID = "preferenceResource"
        private const val PREFERENCE_ROOT = "root"

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
            return (pref.contains(context.getString(R.string.pref_key_player)) &&
                            pref.contains(context.getString(R.string.pref_key_num_of_history))
                    )
        }

    }


}
