package com.daigorian.epcltvapp

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.preference.LeanbackPreferenceFragment
import androidx.leanback.preference.LeanbackSettingsFragment
import androidx.preference.*
import androidx.preference.DialogPreference.TargetFragment
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.M2tsStreamParam


class SettingsFragment : LeanbackSettingsFragment(), TargetFragment {

    companion object {
        const val ARG_START_SCREEN = "start_screen"
        private const val PREFERENCE_RESOURCE_ID = "preferenceResource"
        private const val PREFERENCE_ROOT = "root"
        private const val TAG = "SettingsFragment"

        private const val IP_REGEX_PATTERN = """^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$"""
        private const val PORT_REGEX_PATTERN = """^([1-9]|[1-9][0-9]{1,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])$"""

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

        /**
         * 画面のヘッダにも、その画面のアイコンを出す。
         *
         * leanback のヘッダ（`decor_title`）はアイコン用の枠を持たない素の TextView で、
         * [LeanbackPreferenceFragment.onViewCreated] が文字を入れるだけ。そこで文字の左へ
         * compound drawable として置く。
         *
         * 絵柄は `preferenceScreen.icon` から取るので、XML 側で `app:icon` を書いた画面は
         * 何もしなくてもヘッダに出る（対応表を持たずに済む）。
         * 色はタイトル文字に合わせる。同じ drawable が一覧の項目側でも使われているため、
         * 色を変える前に [android.graphics.drawable.Drawable.mutate] で切り離す。
         */
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            val decorTitle = view.findViewById<TextView>(androidx.leanback.preference.R.id.decor_title)
                ?: return
            val icon = preferenceScreen?.icon?.mutate() ?: return
            val size = resources.getDimensionPixelSize(R.dimen.settings_header_icon_size)
            icon.setTint(decorTitle.currentTextColor)
            icon.setBounds(0, 0, size, size)
            decorTitle.setCompoundDrawablesRelative(icon, null, null, null)
            decorTitle.compoundDrawablePadding =
                resources.getDimensionPixelSize(R.dimen.settings_header_icon_padding)
        }

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

            // レジューム再生・シリーズ自動再生は内蔵プレーヤーの機能なので、
            // 外部プレーヤー選択時は設定ごと隠す。
            val playerPref = preferenceScreen.findPreference(getText(R.string.pref_key_player)) as ListPreference?
            playerPref?.let { updateInternalPlayerOnlyUI(it.value) }
            playerPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                Log.i(TAG, "pref player changed to '$newValue'")
                updateInternalPlayerOnlyUI(newValue as? String)
                true
            }

            // 「録画の再読み込み」。実処理はメイン画面が持っているので、合図だけ置いて帰る。
            // SettingsActivity は windowIsTranslucent なので設定画面を開いても MainActivity は
            // PAUSED 止まりで onStop が呼ばれず、MainFragment のリスナーは登録されたまま。
            // そのためこの書き込みはその場で届く。毎回必ず値が変わるよう時刻を入れる
            // （同じ値だとリスナーが呼ばれない）。
            preferenceScreen.findPreference<Preference>(getText(R.string.pref_key_reload_action))
                ?.setOnPreferenceClickListener {
                    Log.i(TAG, "pref reload clicked")
                    PreferenceManager.getDefaultSharedPreferences(activity!!)
                        .edit()
                        .putLong(getString(R.string.pref_key_reload_request), System.currentTimeMillis())
                        .apply()
                    Toast.makeText(activity, getString(R.string.reload_started), Toast.LENGTH_SHORT).show()
                    true
                }

            // 「アップデートを確認」。サイドバー最下段の同名カードと同じダイアログをここで出す。
            // AppUpdateDialogFragment は androidx の DialogFragment なので supportFragmentManager が要る
            // （SettingsActivity を FragmentActivity にしてあるのはこのため）。
            preferenceScreen.findPreference<Preference>(getText(R.string.pref_key_check_update_action))
                ?.setOnPreferenceClickListener {
                    Log.i(TAG, "pref check_update clicked")
                    (activity as? FragmentActivity)?.let { act ->
                        AppUpdateDialogFragment.newInstance()
                            .show(act.supportFragmentManager, AppUpdateDialogFragment.TAG)
                    }
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

            // ストリームプロファイル選択（Issue #34）: サーバーから取得したプロファイル名は
            // 静的リソースにできないため、EpgStationV2にキャッシュされたstreamConfigから動的に構築する。
            // 未取得(null)の場合は自動ラベルのみのリストになる（表示は壊れない）。
            val streamConfig = EpgStationV2.streamConfig

            val recordedHlsProfilePref = preferenceScreen.findPreference(getText(R.string.pref_key_recorded_hls_profile)) as ListPreference?
            recordedHlsProfilePref?.let {
                val (labels, values) = buildHlsProfileEntries(
                    streamConfig?.recorded?.ts?.hls.orEmpty(),
                    getString(R.string.stream_profile_auto_first)
                )
                it.entries = labels
                it.entryValues = values
            }

            val liveHlsProfilePref = preferenceScreen.findPreference(getText(R.string.pref_key_live_hls_profile)) as ListPreference?
            liveHlsProfilePref?.let {
                val (labels, values) = buildHlsProfileEntries(
                    streamConfig?.live?.ts?.hls.orEmpty(),
                    getString(R.string.stream_profile_auto_first)
                )
                it.entries = labels
                it.entryValues = values
            }

            val liveMpegTsProfilePref = preferenceScreen.findPreference(getText(R.string.pref_key_live_mpegts_profile)) as ListPreference?
            liveMpegTsProfilePref?.let {
                val (labels, values) = buildM2tsProfileEntries(
                    streamConfig?.live?.ts?.m2ts.orEmpty(),
                    getString(R.string.stream_profile_auto_unconverted)
                )
                it.entries = labels
                it.entryValues = values
            }
        }

        private fun buildHlsProfileEntries(names: List<String>, autoLabel: String): Pair<Array<CharSequence>, Array<CharSequence>> {
            val labels = mutableListOf<CharSequence>(autoLabel)
            val values = mutableListOf<CharSequence>("")
            names.forEach { labels.add(it); values.add(it) }
            return labels.toTypedArray() to values.toTypedArray()
        }

        private fun buildM2tsProfileEntries(profiles: List<M2tsStreamParam>, autoLabel: String): Pair<Array<CharSequence>, Array<CharSequence>> {
            val labels = mutableListOf<CharSequence>(autoLabel)
            val values = mutableListOf<CharSequence>("")
            profiles.forEach { p ->
                labels.add(if (p.isUnconverted) "${p.name} (無変換)" else p.name)
                values.add(p.name)
            }
            return labels.toTypedArray() to values.toTypedArray()
        }

        private fun updateInternalPlayerOnlyUI(playerPkgName: String?) {
            val isInternal = playerPkgName == getString(R.string.pref_options_movie_player_val_INTERNAL)
            preferenceScreen.findPreference<Preference>(getText(R.string.pref_key_resume_playback_mode))
                ?.isVisible = isInternal
            preferenceScreen.findPreference<Preference>(getText(R.string.pref_key_series_autoplay))
                ?.isVisible = isInternal
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
