package com.daigorian.epcltvapp

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.leanback.preference.LeanbackPreferenceFragment
import androidx.leanback.preference.LeanbackSettingsFragment
import androidx.preference.*
import androidx.preference.DialogPreference.TargetFragment


class SettingsFragment : LeanbackSettingsFragment(), TargetFragment {

    companion object {
        const val ARG_START_SCREEN = "start_screen"
        private const val PREFERENCE_RESOURCE_ID = "preferenceResource"
        private const val PREFERENCE_ROOT = "root"
        private const val TAG = "SettingsFragment"

        private const val IP_REGEX_PATTERN = """^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$"""
        private const val PORT_REGEX_PATTERN = """^([1-9]|[1-9][0-9]{1,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])$"""
        private const val MAC_REGEX_PATTERN = """^([0-9A-Fa-f]{2}[:\-]){5}[0-9A-Fa-f]{2}$"""

        fun isPreferenceAllExists(context: Context): Boolean {
            val pref = PreferenceManager.getDefaultSharedPreferences(context)
            val useCustomUrl = pref.getBoolean(context.getString(R.string.pref_key_use_custom_base_url), false)
            return if (useCustomUrl) {
                !pref.getString(context.getString(R.string.pref_key_custom_base_url), "").isNullOrEmpty()
            } else {
                val ipRegEx = Regex(pattern = IP_REGEX_PATTERN)
                val ipString = pref.getString(context.getString(R.string.pref_key_ip_addr), "")
                if (ipString?.matches(ipRegEx) != true) return false

                val portRegEx = Regex(pattern = PORT_REGEX_PATTERN)
                val portString = pref.getString(context.getString(R.string.pref_key_port_num), "")
                portString?.matches(portRegEx) == true
            }
        }
    }

    private var mPreferenceFragment: PreferenceFragment? = null

    override fun onPreferenceStartInitialScreen() {
        val startScreen = arguments?.getString(ARG_START_SCREEN)
        Log.i(TAG, "onPreferenceStartInitialScreen startScreen=$startScreen")
        mPreferenceFragment = buildPreferenceFragment(R.xml.preferences, startScreen)
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
        val frag = buildPreferenceFragment(R.xml.preferences, preferenceScreen.key)
        startPreferenceFragment(frag)
        return true
    }

    private fun buildPreferenceFragment(preferenceResId: Int, root: String?): PreferenceFragment {
        val fragment: PreferenceFragment = PrefFragment()
        val args = Bundle()
        args.putInt(PREFERENCE_RESOURCE_ID, preferenceResId)
        args.putString(PREFERENCE_ROOT, root)
        fragment.arguments = args
        return fragment
    }

    override fun <T : Preference?> findPreference(key: CharSequence): T? {
        return mPreferenceFragment!!.findPreference(key)
    }

    class PrefFragment : LeanbackPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val root = arguments.getString(PREFERENCE_ROOT, null)
            val prefResId = arguments.getInt(PREFERENCE_RESOURCE_ID)
            Log.i(TAG, "PrefFragment.onCreatePreferences root=$root")
            if (root == null) {
                addPreferencesFromResource(prefResId)
            } else {
                setPreferencesFromResource(prefResId, root)
            }

            val ipAddressPref = preferenceScreen.findPreference(getText(R.string.pref_key_ip_addr)) as EditTextPreference?
            ipAddressPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                val regex = Regex(pattern = IP_REGEX_PATTERN)
                if (value.toString().matches(regex)) {
                    Log.i(TAG, "pref ip_addr changed to '$value'")
                    true
                } else {
                    Toast.makeText(activity, getString(R.string.not_a_valid_ipv4_addr, value.toString()), Toast.LENGTH_LONG).show()
                    false
                }
            }

            val portNumPref = preferenceScreen.findPreference(getText(R.string.pref_key_port_num)) as EditTextPreference?
            portNumPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                val regex = Regex(pattern = PORT_REGEX_PATTERN)
                if (value.toString().matches(regex)) {
                    Log.i(TAG, "pref port_num changed to '$value'")
                    true
                } else {
                    Toast.makeText(activity, getString(R.string.not_a_valid_port_num, value.toString()), Toast.LENGTH_LONG).show()
                    false
                }
            }

            val useCustomBaseUrlPref = preferenceScreen.findPreference(getText(R.string.pref_key_use_custom_base_url)) as SwitchPreference?
            useCustomBaseUrlPref?.let { enableCustomBaseUrlUI(it.isChecked) }
            useCustomBaseUrlPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                Log.i(TAG, "pref use_custom_base_url changed to $newValue")
                enableCustomBaseUrlUI(newValue as Boolean)
                true
            }

            preferenceScreen.findPreference<Preference>(getText(R.string.pref_key_clear_history))
                ?.setOnPreferenceClickListener {
                    Log.i(TAG, "pref clear_history clicked")
                    PreferenceManager.getDefaultSharedPreferences(activity!!)
                        .edit()
                        .putString("pref_key_search_histories", "")
                        .apply()
                    Toast.makeText(activity, getString(R.string.history_cleared), Toast.LENGTH_SHORT).show()
                    true
                }

            val macAddrPref = preferenceScreen.findPreference(getText(R.string.pref_key_mac_addr)) as EditTextPreference?
            macAddrPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                val macStr = value.toString()
                val regex = Regex(pattern = MAC_REGEX_PATTERN)
                if (macStr.isEmpty() || macStr.matches(regex)) {
                    Log.i(TAG, "pref mac_addr changed to '$macStr'")
                    true
                } else {
                    Toast.makeText(activity, getString(R.string.not_a_valid_mac_addr, macStr), Toast.LENGTH_LONG).show()
                    false
                }
            }
        }

        private fun enableCustomBaseUrlUI(boolean: Boolean) {
            val ipAddressPref = preferenceScreen.findPreference(getText(R.string.pref_key_ip_addr)) as EditTextPreference?
            ipAddressPref?.isVisible = !boolean
            val portNumPref = preferenceScreen.findPreference(getText(R.string.pref_key_port_num)) as EditTextPreference?
            portNumPref?.isVisible = !boolean
            val customBaseUrlPref = preferenceScreen.findPreference(getText(R.string.pref_key_custom_base_url)) as EditTextPreference?
            customBaseUrlPref?.isVisible = boolean
        }
    }
}
