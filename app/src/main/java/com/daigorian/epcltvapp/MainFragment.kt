package com.daigorian.epcltvapp
import android.annotation.SuppressLint
import com.daigorian.epcltvapp.epgstationcaller.*
import com.daigorian.epcltvapp.epgstationv2caller.*

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.util.*

//Retrofit 2
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Loads a grid of cards with movies to browse.
 */
class MainFragment : BrowseSupportFragment() {

    private val mHandler = Handler(Looper.getMainLooper())
    private lateinit var mBackgroundManager: BackgroundManager
    private var mDefaultBackground: Drawable? = null
    private lateinit var mMetrics: DisplayMetrics
    private var mBackgroundTimer: Timer? = null
    private var mBackgroundUri: String? = null
    private var mNeedsReloadAllOnResume = false
    private var mNeedsReloadHistoryOnResume = false
    private var mNeedsCheckConnectionOnResume = false
    private var mConnectionKeyBeforeSettings: String? = null
    private var mSettingsRowAdapter: ArrayObjectAdapter? = null

    private val mCardPresenter = OriginalCardPresenter()
    private val mMainMenuListRowPresenter = ListRowPresenter()
    private val mMainMenuAdapter = MainMenuAdapter(mMainMenuListRowPresenter)

    private val mDisplayPrefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        Log.d(TAG, "prefChanged key=$key isResumed=$isResumed adapterSize=${mMainMenuAdapter.size()} selectedPos=$selectedPosition")
        when (key) {
            getString(R.string.pref_key_rules_order_is_newest_first) -> {
                Log.d(TAG, "prefChanged: rules_order → setSelectedPosition(0) before deleteCategory")
                setSelectedPosition(0, false)
                Log.d(TAG, "prefChanged: rules_order → deleteCategory(RECORDED_BY_RULES+SEARCH_HISTORY) before=${mMainMenuAdapter.size()}")
                mMainMenuAdapter.deleteCategory(Category.RECORDED_BY_RULES)
                mMainMenuAdapter.deleteCategory(Category.SEARCH_HISTORY)
                Log.d(TAG, "prefChanged: rules_order → after deleteCategory adapterSize=${mMainMenuAdapter.size()}")
                updateRows()
            }
            getString(R.string.pref_key_show_thumbnail_background) -> {
                startBackgroundTimer()
            }
            getString(R.string.pref_key_show_empty_rules) -> {
                val showEmptyRules = prefs.getBoolean(getString(R.string.pref_key_show_empty_rules), true)
                Log.d(TAG, "prefChanged: show_empty_rules=$showEmptyRules adapterSize=${mMainMenuAdapter.size()}")
                setSelectedPosition(0, false)
                if (showEmptyRules) updateRows() else mMainMenuAdapter.removeEmptyRuleRows()
            }
            getString(R.string.pref_key_num_of_history) -> {
                Log.d(TAG, "prefChanged: num_of_history → setSelectedPosition(0) + deleteCategory(SEARCH_HISTORY)")
                setSelectedPosition(0, false)
                mMainMenuAdapter.deleteCategory(Category.SEARCH_HISTORY)
                updateRows()
            }
            "pref_key_search_histories" -> {
                Log.d(TAG, "prefChanged: search_histories → setSelectedPosition(0) before deleteCategory")
                setSelectedPosition(0, false)
                Log.d(TAG, "prefChanged: search_histories cleared → deleteCategory(SEARCH_HISTORY) before=${mMainMenuAdapter.size()}")
                mMainMenuAdapter.deleteCategory(Category.SEARCH_HISTORY)
                Log.d(TAG, "prefChanged: search_histories after deleteCategory adapterSize=${mMainMenuAdapter.size()}")
                updateRows()
            }
            getString(R.string.pref_key_mac_addr),
            getString(R.string.pref_key_custom_url_name),
            getString(R.string.pref_key_custom_url_value) -> {
                Log.d(TAG, "prefChanged: $key → syncOptionalSettingsCards")
                syncOptionalSettingsCards()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        showPreviousCrashIfAny()

        adapter = mMainMenuAdapter
        mCardPresenter.objAdapter = mMainMenuAdapter

        // プレイヤー設定などデフォルト値をSharedPreferencesに書き込む（初回のみ）
        androidx.preference.PreferenceManager.setDefaultValues(requireContext(), R.xml.preferences, false)

        if(!SettingsFragment.isPreferenceAllExists(requireContext())){
            Log.i(TAG, "not all Preference exists")
            //設定されていないPreference項目があった場合は設定画面を開く
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
            mNeedsReloadAllOnResume = true

        }else{
            // 画面をスライドインする前の状態にする
            prepareEntranceTransition()
            //設定が最初から読み込めた場合はそれに合わせてAPIを初期化
            //この中で loadRows()がよばれて録画が読み込まれる。
            initEPGStationApi()
            // 画面をスライドインさせる。
            startEntranceTransition()
        }

        prepareBackgroundManager()

        setupUIElements()

        setupEventListeners()
    }

    override fun onResume() {
        Log.i(TAG, "onResume adapterSize=${mMainMenuAdapter.size()} selectedPos=$selectedPosition flags[reloadAll=$mNeedsReloadAllOnResume conn=$mNeedsCheckConnectionOnResume hist=$mNeedsReloadHistoryOnResume]")
        super.onResume()
        Log.d(TAG, "onResume after super: adapterSize=${mMainMenuAdapter.size()} selectedPos=$selectedPosition")
        when {
            mNeedsReloadAllOnResume && SettingsFragment.isPreferenceAllExists(requireContext()) -> {
                Log.d(TAG, "onResume: branch=reloadAll")
                initEPGStationApi()
                mNeedsReloadAllOnResume = false
            }
            mNeedsCheckConnectionOnResume -> {
                val changed = connectionKey() != mConnectionKeyBeforeSettings
                Log.d(TAG, "onResume: branch=checkConnection changed=$changed")
                mNeedsCheckConnectionOnResume = false
                if (changed) {
                    initEPGStationApi()
                }
            }
            mNeedsReloadHistoryOnResume -> {
                Log.d(TAG, "onResume: branch=reloadHistory → deferring to view.post")
                mNeedsReloadHistoryOnResume = false
                view?.post {
                    Log.d(TAG, "onResume: reloadHistory deferred → setSelectedPosition(0) adapterSize=${mMainMenuAdapter.size()}")
                    setSelectedPosition(0, false)
                    Log.d(TAG, "onResume: reloadHistory deferred → deleteCategory(SEARCH_HISTORY) before=${mMainMenuAdapter.size()}")
                    mMainMenuAdapter.deleteCategory(Category.SEARCH_HISTORY)
                    Log.d(TAG, "onResume: reloadHistory deferred → after deleteCategory adapterSize=${mMainMenuAdapter.size()}")
                    updateRows()
                }
            }
            else -> {
                Log.d(TAG, "onResume: branch=else → updateRows")
                updateRows()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: adapterSize=${mMainMenuAdapter.size()} selectedPos=$selectedPosition")
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: registering prefListener adapterSize=${mMainMenuAdapter.size()}")
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(mDisplayPrefChangeListener)
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: unregistering prefListener adapterSize=${mMainMenuAdapter.size()}")
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(mDisplayPrefChangeListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: " + mBackgroundTimer?.toString())
        mBackgroundTimer?.cancel()
    }

    private fun initEPGStationApi(){
        //Preferenceに設定されているAPIバージョン、IPアドレス、ポート番号、１回あたりの取得数を読み込んでAPIをセットアップする。
        EpgStation.api = null //apiをいったん初期化
        EpgStationV2.api = null //apiをいったん初期化

        //設定値を取得
        val pref = PreferenceManager.getDefaultSharedPreferences(context)
        val useCustomBaseURL = pref.getBoolean(getString(R.string.pref_key_use_custom_base_url),false)

        //base URL
        val baseUrl = if (useCustomBaseURL) {
            //カスタムURL ON の時はそちらを読み込む
             pref.getString(
                getString(R.string.pref_key_custom_base_url),
                getString(R.string.pref_val_custom_base_url_default)
            )!!

        }else {
            //カスタムURL OFF の時はIPとPortからURLを生成する
            val ipAddress = pref.getString(
                    getString(R.string.pref_key_ip_addr),
                    getString(R.string.pref_val_ip_addr_default)
                )!!
            val port = pref.getString(
                    getString(R.string.pref_key_port_num),
                    getString(R.string.pref_val_port_num_default)
                )!!
            "http://$ipAddress:$port/api/"
        }

        //バージョンチェックして適切なバージョンのAPIを初期化
        try {
            EpgStationV2VersionChecker(baseUrl).api.getVersion()
                .enqueue(object : Callback<Version> {
                    override fun onResponse(call: Call<Version>, response: Response<Version>) {
                        if (response.body() != null) {
                            //Version 2で初期化
                            Log.d(TAG, "initEPGStationApi() detect Version 2.x.x")
                            EpgStationV2.initAPI(baseUrl)
                            EpgStationV2.fetchChannels()
                            loadRows()
                        } else {
                            //Version 1で初期化
                            Log.d(TAG, "initEPGStationApi() detect Version 1.x.x")
                            EpgStationV2.api = null
                            EpgStation.initAPI(baseUrl)
                            EpgStation.fetchChannels()
                            loadRows()
                        }
                    }

                    override fun onFailure(call: Call<Version>, t: Throwable) {
                        Log.d(TAG, "initEPGStationApi() getVersion API Failure")
                        Toast.makeText(
                            context!!,
                            getString(R.string.connect_epgstation_failed) + "\n" + getString(R.string.please_check_ip_and_port) ,
                            Toast.LENGTH_LONG
                        ).show()
                        loadRows()

                    }
                })
        } catch(e:Exception){
            Toast.makeText(
                requireContext(),
                getString(R.string.connect_epgstation_failed)  + "\n" +  e.message,
                Toast.LENGTH_LONG
            ).show()
            loadRows()
        }



    }

    private fun prepareBackgroundManager() {

        mBackgroundManager = BackgroundManager.getInstance(activity)
        mBackgroundManager.attach(requireActivity().window)
        mBackgroundManager.color = ContextCompat.getColor(requireContext(), R.color.background_no_thumbnail)
        mDefaultBackground = ContextCompat.getDrawable(requireContext(), R.drawable.default_background)
        mMetrics = resources.displayMetrics

    }

    private fun setupUIElements() {
        title = getString(R.string.browse_title)
        // over title
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        // set fastLane (or headers) background color
        brandColor = ContextCompat.getColor(requireContext(), R.color.background_epgstation)
        // 検索オーブ: アイコン #363636、背景はサイドバー文字色に合わせてフォーカスで変化
        searchAffordanceColors = SearchOrbView.Colors(
            Color.argb(0x66, 0xFF, 0xFF, 0xFF),  // 非フォーカス: 40% 白
            Color.WHITE,                           // フォーカス時: 100% 白
            Color.parseColor("#363636")            // アイコン色
        )

        // カスタムヘッダープレゼンターでサイドバーアイコンを設定
        // SectionRow は DividerRow と同様に専用インスタンスを使い view pool を分離する。
        // onCreateViewHolder でフォーカス不可を設定することで再利用時も安全に非フォーカスを維持できる。
        setHeaderPresenterSelector(object : PresenterSelector() {
            private val iconPresenter = IconRowHeaderPresenter()
            private val dividerPresenter = DividerPresenter()
            private val sectionPresenter = object : IconRowHeaderPresenter() {
                override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
                    return super.onCreateViewHolder(parent).also { vh ->
                        vh.view.isFocusable = false
                        vh.view.isFocusableInTouchMode = false
                    }
                }
            }
            override fun getPresenter(item: Any?): Presenter = when (item) {
                is DividerRow -> dividerPresenter
                is SectionRow -> sectionPresenter
                else -> iconPresenter
            }
        })
    }

    private fun updateRows() {

        EpgStationV2.api?.let { api ->
            // EPGStation V2.x.x　の場合だけ「ライブ視聴」列を作る

            val liveHeaderId = Category.LIVE_CHANNELS.ordinal.toLong()*10000

            api.getChannels().enqueue(object : Callback<List<ChannelItem>> {
                override fun onResponse(call: Call<List<ChannelItem>>, response: Response<List<ChannelItem>>) {
                    response.body()?.let { channels ->
                        if (channels.isEmpty()) {
                            if (mMainMenuAdapter.getListRowByHeaderId(liveHeaderId) != null) {
                                mMainMenuAdapter.deleteCategory(Category.LIVE_CHANNELS)
                            }
                        } else {
                            val currentListRow = mMainMenuAdapter.getListRowByHeaderId(liveHeaderId)
                            val listRowAdapter = if (currentListRow == null) {
                                ArrayObjectAdapter(mCardPresenter).also { adapter ->
                                    val header = HeaderItem(liveHeaderId, getString(R.string.live_channels))
                                    mMainMenuAdapter.addToCategory(Category.LIVE_CHANNELS, ListRow(header, adapter))
                                }
                            } else {
                                currentListRow.adapter as ArrayObjectAdapter
                            }

                            //既存のリストにあって、レスポンスにないアイテムの削除
                            var horizontalIndex = 0
                            while (horizontalIndex < listRowAdapter.size()) {
                                var found = false
                                channels.forEach {
                                    if (listRowAdapter.get(horizontalIndex).equals(it)) found = true
                                }
                                if (!found) {
                                    listRowAdapter.removeItems(horizontalIndex, 1)
                                } else {
                                    horizontalIndex += 1
                                }
                            }

                            //レスポンスにあって、既存のリストにないアイテムの追加
                            channels.forEachIndexed { index, it ->
                                if (listRowAdapter.indexOf(it) == -1) {
                                    listRowAdapter.add(index, it)
                                }
                            }
                        }
                    }
                }
                override fun onFailure(call: Call<List<ChannelItem>>, t: Throwable) {
                    Log.d(TAG, "loadRows() getChannels API Failure")
                }
            })
        }

        EpgStationV2.api?.let{ api ->
            // EPGStation V2.x.x　の場合だけ「録画中」列を作る

            //行のID
            val headerId = Category.ON_RECORDING.ordinal.toLong()*10000

            //同じIDを持つ既存の行があるか検索
            val existingListRow = mMainMenuAdapter.getListRowByHeaderId(headerId)

            // APIでロードするアイテムの数。既存のアイテムがある場合はその数だけロードする
            val apiLimit = if (existingListRow == null)
                EpgStationV2.default_limit.toInt()
            else
                (existingListRow.adapter as ArrayObjectAdapter).size()

            api.getRecording(limit = apiLimit).enqueue(object : Callback<Records> {
                override fun onResponse(call: Call<Records>, response: Response<Records>) {
                    response.body()?.let { getRecordingResponse ->
                        if (getRecordingResponse.records.isEmpty()) {
                            // 録画中アイテムがなければ行を削除する
                            if (mMainMenuAdapter.getListRowByHeaderId(headerId) != null) {
                                mMainMenuAdapter.deleteCategory(Category.ON_RECORDING)
                            }
                        } else {
                            // 行がなければ新たに作成する
                            val currentListRow = mMainMenuAdapter.getListRowByHeaderId(headerId)
                            val listRowAdapter = if (currentListRow == null) {
                                ArrayObjectAdapter(mCardPresenter).also { adapter ->
                                    val header = HeaderItem(headerId, getString(R.string.now_on_recording))
                                    mMainMenuAdapter.addToCategory(Category.ON_RECORDING, ListRow(header, adapter))
                                }
                            } else {
                                currentListRow.adapter as ArrayObjectAdapter
                            }

                            //既存のリストにあって、レスポンスにないアイテムの削除
                            var horizontalIndex = 0
                            while(horizontalIndex < listRowAdapter.size()) {
                                var found = false
                                getRecordingResponse.records.forEach {
                                  if(listRowAdapter.get(horizontalIndex).equals(it) ) found = true
                                }
                                if (!found) {
                                    listRowAdapter.removeItems(horizontalIndex,1)
                                }else {
                                    horizontalIndex += 1
                                }
                            }

                            //レスポンスにあって、既存のリストにないアイテムの追加
                            getRecordingResponse.records.forEachIndexed { index, it ->
                                if(listRowAdapter.indexOf(it) == -1){
                                    listRowAdapter.add(index,it)
                                }
                            }
                        }
                    }
                }
                override fun onFailure(call: Call<Records>, t: Throwable) {
                    Log.d(TAG,"loadRows() getRecorded API Failure")
                    Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
                }
            })
        }

        //最近の録画の列
        mMainMenuAdapter.updateContentsListRowWithCategory(
            GetRecordedParam(),
            GetRecordedParamV2(),
            getString(R.string.recent_videos),
            Category.RECENTLY_RECORDED,
            0L
        )


        //ルール・履歴の並び順を表すフラグ。デフォルトfalse。
        val isNewestFirst = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.pref_key_rules_order_is_newest_first),false)

        //履歴行の追加
        val historyList = SearchFragment.getHistory(requireContext())
        val orderedHistory = if (isNewestFirst) historyList.asReversed() else historyList
        orderedHistory.forEachIndexed { index, it ->
            mMainMenuAdapter.updateContentsListRowWithCategory(
                GetRecordedParam(keyword = it),
                GetRecordedParamV2(keyword = it),
                it,
                Category.SEARCH_HISTORY,
                index.toLong()
            )
        }

        //次の横の列。録画ルール。録画ルールの数だけ行が増える。
        EpgStation.api?.getRulesList()?.enqueue(object : Callback<List<RuleList>> {
            override fun onResponse(call: Call<List<RuleList>>, response: Response<List<RuleList>>) {
                response.body()?.let{ it ->
                    val rules = if(isNewestFirst){it.reversed()}else{it}
                    val orderedIds = rules.map { rule -> rule.id.toLong() }
                    rules.forEach { rule ->

                        //録画ルールにキーワードが設定されていない場合、キーワードの代わりにルールIDをセット
                        val keyword:String = if ( rule.keyword.isNullOrEmpty() ){
                            getString(R.string.rule_id_is_x, rule.id.toString())
                        }else{
                            rule.keyword
                        }
                        mMainMenuAdapter.updateContentsListRowWithCategory(
                            GetRecordedParam(rule= rule.id),
                            GetRecordedParamV2(ruleId= rule.id),
                            keyword,
                            Category.RECORDED_BY_RULES,
                            rule.id,
                            orderedIds
                        )
                    }
                }
            }
            override fun onFailure(call: Call<List<RuleList>>, t: Throwable) {
                Log.d(TAG,"loadRows() getRulesList API Failure")
                Toast.makeText(context!!, R.string.connect_epgstation_failed, Toast.LENGTH_LONG).show()
            }
        })
        EpgStationV2.api?.getRules(limit=Int.MAX_VALUE)?.enqueue(object : Callback<Rules> {
            override fun onResponse(call: Call<Rules>, response: Response<Rules>) {
                response.body()?.rules?.let{ it ->
                    val rules = if(isNewestFirst){it.reversed()}else{it}
                    val orderedIds = rules.map { rule -> rule.id.toLong() }
                    rules.forEach { rule ->

                        //録画ルールにキーワードが設定されていない場合、キーワードの代わりにルールIDをセット
                        val keyword:String = if ( rule.searchOption?.keyword.isNullOrEmpty() ){
                            getString(R.string.rule_id_is_x, rule.id.toString())
                        }else{
                            rule.searchOption?.keyword!!
                        }
                        mMainMenuAdapter.updateContentsListRowWithCategory(
                            GetRecordedParam(rule= rule.id),
                            GetRecordedParamV2(ruleId= rule.id),
                            keyword,
                            Category.RECORDED_BY_RULES,
                            rule.id,
                            orderedIds
                        )
                    }
                }
            }
            override fun onFailure(call: Call<Rules>, t: Throwable) {
                Log.d(TAG,"loadRows() getRulesList API Failure")
                Toast.makeText(context!!, R.string.connect_epgstation_failed, Toast.LENGTH_LONG).show()
            }
        })

    }

    private fun loadRows() {

        //内容クリア
        mMainMenuAdapter.clear()

        //コンテンツをロード。
        updateRows()

        //"番組名更新"　の単体アクションが乗る行
        val programRefreshHeader = HeaderItem(-Category.PROGRAM_NAME_REFRESH.ordinal.toLong(), getString(R.string.refresh_program_names))
        val programRefreshAdapter = ArrayObjectAdapter(SettingsCardPresenter())
        programRefreshAdapter.add(SettingsCardPresenter.Item(
            R.drawable.ic_settings_reload,
            getString(R.string.refresh_program_names),
            SettingsCardPresenter.Item.Action.REFRESH_PROGRAM_NAMES
        ))
        mMainMenuAdapter.addToCategory(Category.PROGRAM_NAME_REFRESH, ListRow(programRefreshHeader, programRefreshAdapter))

        //"設定"　のボタンが乗る行
        val gridHeader = HeaderItem(-Category.SETTINGS.ordinal.toLong(), getString(R.string.settings))
        val gridPresenter = SettingsCardPresenter()
        val gridRowAdapter = ArrayObjectAdapter(gridPresenter)
        mSettingsRowAdapter = gridRowAdapter

        gridRowAdapter.add(SettingsCardPresenter.Item(
            R.drawable.ic_settings_connection,
            getString(R.string.settings_connection),
            SettingsCardPresenter.Item.Action.CONNECTION
        ))
        gridRowAdapter.add(SettingsCardPresenter.Item(
            R.drawable.ic_settings_player,
            getString(R.string.settings_player),
            SettingsCardPresenter.Item.Action.PLAYER
        ))
        gridRowAdapter.add(SettingsCardPresenter.Item(
            R.drawable.ic_settings_image,
            getString(R.string.settings_display),
            SettingsCardPresenter.Item.Action.DISPLAY
        ))
        gridRowAdapter.add(SettingsCardPresenter.Item(
            R.drawable.ic_settings_reload,
            getString(R.string.reload),
            SettingsCardPresenter.Item.Action.RELOAD
        ))

        mMainMenuAdapter.addToCategory(Category.SETTINGS, ListRow(gridHeader, gridRowAdapter))

        syncOptionalSettingsCards()

    }

    /**
     * 設定内容（MACアドレス・カスタムURL）に応じて、設定行の末尾の任意カード
     * （Wake on LANで起動／カスタムURL実行）を並べ直す。
     * 固定カード（接続設定・プレイヤー設定・表示設定・再読み込み）より後ろを一旦すべて外してから作り直す。
     */
    private fun syncOptionalSettingsCards() {
        val adapter = mSettingsRowAdapter ?: return
        while (adapter.size() > FIXED_SETTINGS_CARD_COUNT) {
            adapter.removeItems(adapter.size() - 1, 1)
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val mac = prefs.getString(getString(R.string.pref_key_mac_addr), "")
        if (!mac.isNullOrEmpty()) {
            adapter.add(SettingsCardPresenter.Item(
                R.drawable.ic_settings_power,
                getString(R.string.wake_on_lan),
                SettingsCardPresenter.Item.Action.WAKE_ON_LAN
            ))
        }

        val customUrlName = prefs.getString(getString(R.string.pref_key_custom_url_name), "")
        val customUrlValue = prefs.getString(getString(R.string.pref_key_custom_url_value), "")
        if (!customUrlName.isNullOrEmpty() && !customUrlValue.isNullOrEmpty()) {
            adapter.add(SettingsCardPresenter.Item(
                0,
                customUrlName,
                SettingsCardPresenter.Item.Action.CUSTOM_URL,
                iconChar = "⤴"
            ))
        }
    }


    /** 設定行を保持したまま、コンテンツ行だけをクリアして再読み込みする */
    private fun reloadContentRows() {
        listOf(Category.LIVE_CHANNELS, Category.ON_RECORDING, Category.RECENTLY_RECORDED, Category.SEARCH_HISTORY, Category.RECORDED_BY_RULES)
            .forEach { mMainMenuAdapter.deleteCategory(it) }
        updateRows()
    }

    /** 「番組名更新」ボタン押下時に、現在放送中の番組名だけをまとめて取り直してカードに反映する */
    private fun refreshLiveProgramNames() {
        val headerId = Category.LIVE_CHANNELS.ordinal.toLong()*10000
        val adapter = (mMainMenuAdapter.getListRowByHeaderId(headerId)?.adapter as? ArrayObjectAdapter) ?: return

        EpgStationV2.api?.getScheduleOnAir()?.enqueue(object : Callback<List<Schedule>> {
            override fun onResponse(call: Call<List<Schedule>>, response: Response<List<Schedule>>) {
                val programByChannelId = response.body()
                    ?.associate { it.channel.id to it.programs.firstOrNull()?.name }
                    ?: return
                for (i in 0 until adapter.size()) {
                    (adapter.get(i) as? ChannelItem)?.let { it.currentProgramName = programByChannelId[it.id] }
                }
                activity?.runOnUiThread {
                    adapter.notifyArrayItemRangeChanged(0, adapter.size())
                }
            }
            override fun onFailure(call: Call<List<Schedule>>, t: Throwable) {
                Log.d(TAG,"refreshLiveProgramNames() getScheduleOnAir API Failure")
                Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
            }
        })
    }

    /** USBデバッグなしでもクラッシュ内容を確認できるよう、前回起動時のクラッシュログがあれば表示する */
    private fun showPreviousCrashIfAny() {
        val crashFile = java.io.File(requireContext().filesDir, EpgTvApplication.CRASH_LOG_FILENAME)
        if (!crashFile.exists()) return
        val content = try { crashFile.readText() } catch (_: Exception) { "" }
        crashFile.delete()
        if (content.isBlank()) return
        androidx.appcompat.app.AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_MinWidth)
            .setTitle("前回のクラッシュ内容")
            .setMessage(content)
            .setPositiveButton("閉じる") { _, _ -> }
            .create().show()
    }

    /** 接続設定の変化検知用フィンガープリント */
    private fun connectionKey(): String {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val useCustomUrl = prefs.getBoolean(getString(R.string.pref_key_use_custom_base_url), false)
        return if (useCustomUrl) {
            prefs.getString(getString(R.string.pref_key_custom_base_url), "") ?: ""
        } else {
            val ip = prefs.getString(getString(R.string.pref_key_ip_addr), "") ?: ""
            val port = prefs.getString(getString(R.string.pref_key_port_num), "") ?: ""
            "$ip:$port"
        }
    }

    private fun setupEventListeners() {
        setOnSearchClickedListener {
            Intent(activity, SearchActivity::class.java).also { intent ->
                startActivity(intent)
                mNeedsReloadHistoryOnResume = true
            }
        }

        onItemViewClickedListener = ItemViewClickedListener()
        onItemViewSelectedListener = ItemViewSelectedListener()
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        @SuppressLint("ApplySharedPref")
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {

            when (item) {
                is RecordedProgram -> {
                    // EPGStation Version 1.x.x のアイテム
                    Log.d(TAG, "Item: $item")
                    val intent = Intent(context!!, DetailsActivity::class.java)
                    intent.putExtra(DetailsActivity.RECORDEDPROGRAM, item)

                    val bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity!!,
                        (itemViewHolder.view as ImageCardView).mainImageView,
                        DetailsActivity.SHARED_ELEMENT_NAME
                    )
                        .toBundle()
                    startActivity(intent, bundle)
                }
                is RecordedItem -> {
                    // EPGStation Version 2.x.x のアイテム
                    Log.d(TAG, "Item: $item")
                    val intent = Intent(context!!, DetailsActivity::class.java)
                    intent.putExtra(DetailsActivity.RECORDEDITEM, item)

                    val bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity!!,
                        (itemViewHolder.view as ImageCardView).mainImageView,
                        DetailsActivity.SHARED_ELEMENT_NAME
                    )
                        .toBundle()
                    startActivity(intent, bundle)
                }
                is ChannelItem -> {
                    // ライブ視聴（単押し=mpegts直送）。詳細画面を経由せず直接再生する。
                    Log.d(TAG, "Item: $item")
                    val intent = Intent(context!!, PlaybackActivity::class.java)
                    intent.putExtra(DetailsActivity.IS_LIVE_MPEGTS, true)
                    intent.putExtra(DetailsActivity.CHANNEL_ID, item.id)
                    intent.putExtra(DetailsActivity.CHANNEL_NAME, item.halfWidthName.ifEmpty { item.name })
                    startActivity(intent)
                }
                is SettingsCardPresenter.Item -> {
                    when (item.action) {
                        SettingsCardPresenter.Item.Action.CONNECTION -> {
                            mConnectionKeyBeforeSettings = connectionKey()
                            mNeedsCheckConnectionOnResume = true
                            val intent = Intent(context!!, SettingsActivity::class.java)
                            intent.putExtra(SettingsActivity.EXTRA_START_SCREEN, getString(R.string.pref_key_screen_connection))
                            startActivity(intent)
                        }
                        SettingsCardPresenter.Item.Action.PLAYER -> {
                            val intent = Intent(context!!, SettingsActivity::class.java)
                            intent.putExtra(SettingsActivity.EXTRA_START_SCREEN, getString(R.string.pref_key_screen_player))
                            startActivity(intent)
                        }
                        SettingsCardPresenter.Item.Action.DISPLAY -> {
                            val intent = Intent(context!!, SettingsActivity::class.java)
                            intent.putExtra(SettingsActivity.EXTRA_START_SCREEN, getString(R.string.pref_key_screen_display))
                            startActivity(intent)
                        }
                        SettingsCardPresenter.Item.Action.RELOAD -> {
                            reloadContentRows()
                        }
                        SettingsCardPresenter.Item.Action.REFRESH_PROGRAM_NAMES -> {
                            refreshLiveProgramNames()
                        }
                        SettingsCardPresenter.Item.Action.WAKE_ON_LAN -> {
                            val mac = PreferenceManager.getDefaultSharedPreferences(requireContext())
                                .getString(getString(R.string.pref_key_mac_addr), null)
                            if (!mac.isNullOrEmpty()) {
                                Thread {
                                    try {
                                        WakeOnLan.send(mac)
                                        activity?.runOnUiThread {
                                            Toast.makeText(requireContext(), getString(R.string.wake_on_lan_sent), Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "WakeOnLan.send failed", e)
                                        activity?.runOnUiThread {
                                            Toast.makeText(requireContext(), getString(R.string.wake_on_lan_failed), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }.start()
                            }
                        }
                        SettingsCardPresenter.Item.Action.CUSTOM_URL -> {
                            val url = PreferenceManager.getDefaultSharedPreferences(requireContext())
                                .getString(getString(R.string.pref_key_custom_url_value), null)
                            if (!url.isNullOrEmpty()) {
                                Thread {
                                    try {
                                        CustomUrlAction.get(url)
                                        activity?.runOnUiThread {
                                            Toast.makeText(requireContext(), getString(R.string.custom_url_sent), Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "CustomUrlAction.get failed", e)
                                        activity?.runOnUiThread {
                                            Toast.makeText(requireContext(), getString(R.string.custom_url_failed), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }.start()
                            }
                        }
                    }
                }
            }
        }
    }



    private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
        override fun onItemSelected(
            itemViewHolder: Presenter.ViewHolder?, item: Any?,
            rowViewHolder: RowPresenter.ViewHolder, row: Row
        ) {
            when (item) {
                is RecordedProgram -> {
                    // EPGStation Version 1.x.x
                    mBackgroundUri = EpgStation.getThumbnailURL(item.id.toString())
                    startBackgroundTimer()
                }
                is RecordedItem -> {
                    // EPGStation Version 2.x.x
                    mBackgroundUri = if(!item.thumbnails.isNullOrEmpty()) {
                        EpgStationV2.getThumbnailURL(item.thumbnails[0].toString())
                    } else {
                        EpgStationV2.getThumbnailURL("") // ありえないURLでエラーに落とす。
                    }
                    startBackgroundTimer()
                }
                is GetRecordedParam -> {
                    // EPGStation Version 1.x.x の続きを取得するアイテム
                    val adapter =  ((row as ListRow).adapter as ArrayObjectAdapter)

                    //APIで続きを取得して続きに加えていく
                    // EPGStation V1.x.x
                    EpgStation.api?.getRecorded(
                        limit = item.limit,
                        offset = item.offset,
                        reverse = item.reverse,
                        rule = item.rule,
                        genre1 = item.genre1,
                        channel = item.channel,
                        keyword = item.keyword,
                        hasTs = item.hasTs,
                        recording = item.recording
                    )?.enqueue(object : Callback<GetRecordedResponse> {
                        override fun onResponse(call: Call<GetRecordedResponse>, response: Response<GetRecordedResponse>) {
                            response.body()?.let { getRecordedResponse ->

                                //APIのレスポンスをひとつづつアイテムとして加える。最初のアイテムだけ、Loadingアイテムを置き換える
                                //先にremoveしてaddすると高速でスクロールさせたときに描画とremoveがぶつかって落ちるのであえてreplaceに。
                                getRecordedResponse.recorded.forEachIndexed {  index, recordedProgram ->
                                    if(index == 0) {
                                        adapter.replace(adapter.indexOf(item),recordedProgram)
                                    }else{
                                        adapter.add(recordedProgram)
                                    }
                                }
                                //続きがあるなら"次を読み込む"を置く。
                                val numOfItem = getRecordedResponse.recorded.count().toLong() + item.offset
                                if (numOfItem < getRecordedResponse.total) {
                                    adapter.add(item.copy(offset = numOfItem))
                                }

                            }
                        }
                        override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                            Log.d(TAG,"loadRows() getRecorded API Failure")
                            Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
                        }
                    })
                }
                is GetRecordedParamV2 -> {
                    // EPGStation Version 1.x.x の続きを取得するアイテム
                    val adapter =  ((row as ListRow).adapter as ArrayObjectAdapter)

                    //APIで続きを取得して続きに加えていく
                    // EPGStation V2.x.x
                    EpgStationV2.api?.getRecorded(
                        isHalfWidth = item.isHalfWidth,
                        offset = item.offset,
                        limit = item.limit,
                        isReverse = item.isReverse,
                        ruleId = item.ruleId,
                        channelId = item.channelId,
                        genre = item.genre,
                        keyword = item.keyword,
                        hasOriginalFile = item.hasOriginalFile
                    )?.enqueue(object : Callback<Records> {
                        override fun onResponse(call: Call<Records>, response: Response<Records>) {
                            response.body()?.let { responseRoot ->

                                //APIのレスポンスをひとつづつアイテムとして加える。最初のアイテムだけ、Loadingアイテムを置き換える
                                //先にremoveしてaddすると高速でスクロールさせたときに描画とremoveがぶつかって落ちるのであえてreplaceに。
                                responseRoot.records.forEachIndexed {  index, recordedProgram ->
                                    if(index == 0) {
                                        adapter.replace(adapter.indexOf(item),recordedProgram)
                                    }else{
                                        adapter.add(recordedProgram)
                                    }
                                }
                                //続きがあるなら"次を読み込む"を置く。
                                val numOfItem = responseRoot.records.count().toLong() + item.offset
                                if (numOfItem < responseRoot.total) {
                                    adapter.add(item.copy(offset = numOfItem))
                                }

                            }
                        }
                        override fun onFailure(call: Call<Records>, t: Throwable) {
                            Log.d(TAG,"loadRows() getRecorded API Failure")
                            Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
                        }
                    })
                }

            }
        }
    }

    private fun updateBackground(uri: String?) {
        val width = mMetrics.widthPixels
        val height = mMetrics.heightPixels

        //Glideでイメージを取得する際にBasic認証が必要な場合はヘッダを付与してやる
        val glideUrl = if(EpgStation.api!=null && EpgStation.authForGlide!=null){
            GlideUrl( uri, EpgStation.authForGlide)
        }else if(EpgStationV2.api!=null && EpgStationV2.authForGlide!=null){
            GlideUrl( uri, EpgStationV2.authForGlide)
        }else{
            GlideUrl ( uri )
        }

        Glide.with(requireContext())
            .load(glideUrl)
            .centerCrop()
            .error(mDefaultBackground)
            .into<CustomTarget<Drawable>>(
                object : CustomTarget<Drawable>(width, height) {
                    override fun onResourceReady(
                        drawable: Drawable,
                        transition: Transition<in Drawable>?
                    ) {
                        mBackgroundManager.drawable = drawable
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
        mBackgroundTimer?.cancel()
    }

    private fun startBackgroundTimer() {
        val showThumbnailBg = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean(getString(R.string.pref_key_show_thumbnail_background), false)
        if (!showThumbnailBg) {
            mBackgroundManager.color = ContextCompat.getColor(requireContext(), R.color.background_no_thumbnail)
            return
        }
        mBackgroundTimer?.cancel()
        mBackgroundTimer = Timer()
        mBackgroundTimer?.schedule(UpdateBackgroundTask(), BACKGROUND_UPDATE_DELAY.toLong())
    }

    private inner class UpdateBackgroundTask : TimerTask() {

        override fun run() {
            mHandler.post { updateBackground(mBackgroundUri) }
        }
    }

    enum class Category {
        //メニューはこの順番で並びます。
        LIVE_CHANNELS,
        PROGRAM_NAME_REFRESH,
        ON_RECORDING,
        RECENTLY_RECORDED,
        SEARCH_HISTORY,
        RECORDED_BY_RULES,
        SETTINGS
    }

    private inner class MainMenuAdapter(presenter: Presenter?) : DeleteEnabledArrayObjectAdapter(presenter) {

        private val numOfRowInCategory = IntArray(Category.values().size)

        override fun clear() {
            synchronized(this) {
                numOfRowInCategory.forEachIndexed { index,_ ->
                    numOfRowInCategory[index] = 0
                }
                super.clear()
            }
        }

        fun addToCategory(cat:Category,item: Any?){

            synchronized(this){
                //行を加える場所を計算する
                val index = numOfRowInCategory.copyOfRange(0,cat.ordinal+1).sum()
                //行を加える。
                super.add(index,item)
                numOfRowInCategory[cat.ordinal]++

                //もし先ほど加えた行がそのカテゴリの最初の行だった場合
                if(numOfRowInCategory[cat.ordinal] == 1){
                    when(cat){
                        Category.LIVE_CHANNELS -> {
                            //一行しかないのでセクション行は入れない。
                            //ライブ視聴は一番上のグループなので区切り線は入れない。
                        }
                        Category.PROGRAM_NAME_REFRESH -> {
                            //一行しかないのでセクション行は入れない。
                            //ライブ視聴の付属アクションなので区切り線は入れない。
                        }
                        Category.ON_RECORDING -> {
                            //一行しかないのでセクション行は入れない。
                            //録画中と最近の録画は一番上のグループなので区切り線は入れない。
                        }
                        Category.RECENTLY_RECORDED ->{
                            //一行しかないのでセクション行は入れない。
                            //録画中と最近の録画は一番上のグループなので区切り線は入れない。
                        }
                        Category.SEARCH_HISTORY ->{
                            //検索履歴というセクション行を、さらに上に加える
                            super.add(index, SectionRow(HeaderItem(-Category.SEARCH_HISTORY.ordinal.toLong(), getString(R.string.search_history))))
                            numOfRowInCategory[cat.ordinal]++
                            //さらにその上に区切り線を乗せる。
                            super.add(index,DividerRow())
                            numOfRowInCategory[cat.ordinal]++
                        }
                        Category.RECORDED_BY_RULES ->{
                            //録画ルールというセクション行を、さらに上に加える
                            super.add(index, SectionRow(HeaderItem(-Category.RECORDED_BY_RULES.ordinal.toLong(), getString(R.string.by_rec_rules))))
                            numOfRowInCategory[cat.ordinal]++
                            //さらにその上に区切り線を乗せる。
                            super.add(index,DividerRow())
                            numOfRowInCategory[cat.ordinal]++
                        }
                        Category.SETTINGS->{
                            //その上に区切り線を乗せる。
                            super.add(index,DividerRow())
                            numOfRowInCategory[cat.ordinal]++
                        }
                    }
                }
            }//synchronized
        }

        fun deleteCategory(cat:Category){
            val start = numOfRowInCategory.copyOfRange(0,cat.ordinal).sum()
            synchronized(this) {
                super.removeItems(start ,numOfRowInCategory[cat.ordinal] )
                numOfRowInCategory[cat.ordinal] = 0
            }//synchronized
        }

        fun removeRowFromCategory(cat: Category, headerId: Long) {
            synchronized(this) {
                var rowIndex = -1
                for (i in 0 until size()) {
                    val row = get(i)
                    if (row is ListRow && row.headerItem.id == headerId) {
                        rowIndex = i
                        break
                    }
                }
                if (rowIndex == -1) return

                super.removeItems(rowIndex, 1)
                numOfRowInCategory[cat.ordinal]--

                // DividerRow + SectionRow しか残っていない場合はそれも除去する
                if (numOfRowInCategory[cat.ordinal] == 2) {
                    val start = numOfRowInCategory.copyOfRange(0, cat.ordinal).sum()
                    super.removeItems(start, 2)
                    numOfRowInCategory[cat.ordinal] = 0
                }
            }
        }

        fun updateContentsListRowWithCategory(v1Pram:GetRecordedParam,v2Param:GetRecordedParamV2,title:String,category:Category,idInCategory:Long,orderedIds:List<Long>?=null){

            val headerId = category.ordinal.toLong()*10000 + idInCategory

            // 同じIDを持つ行が存在するかどうか確認する
            val listRow = getListRowByHeaderId(headerId)

            // 既存の行があれば、それを取得する。なければ新たに作る。
            val listRowAdapter = if(listRow==null)
                ArrayObjectAdapter(mCardPresenter)
            else
                listRow.adapter as ArrayObjectAdapter

            // 既存の行がなければ追加する。ただし RECORDED_BY_RULES かつ showEmptyRules=false の場合は
            // API レスポンス確認後に追加する（一時的な空行挿入による mSelectedPosition 増加を防ぐため）。
            val showEmptyRulesPref = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(getString(R.string.pref_key_show_empty_rules), true)
            val addedUpfront = if (listRow == null) {
                val deferred = category == Category.RECORDED_BY_RULES && !showEmptyRulesPref
                if (!deferred) {
                    addToCategory(category, ListRow(HeaderItem(headerId, title), listRowAdapter))
                }
                !deferred
            } else {
                true
            }

            // すでにロードされている数。
            val numOfLoaded = if (listRow==null)
                0L
            else
                listRowAdapter.size().toLong()

            // APIのコールバックでListRowの中身をセットするように仕掛ける
            // EPGStation V1.x.x
            EpgStation.api?.getRecorded(
                limit = if(numOfLoaded>v1Pram.limit) numOfLoaded else v1Pram.limit,
                offset = v1Pram.offset,
                reverse = v1Pram.reverse,
                rule = v1Pram.rule,
                genre1 = v1Pram.genre1,
                channel = v1Pram.channel,
                keyword = v1Pram.keyword,
                hasTs = v1Pram.hasTs,
                recording = v1Pram.recording )?.enqueue(object : Callback<GetRecordedResponse> {

                override fun onResponse(call: Call<GetRecordedResponse>, response: Response<GetRecordedResponse>) {
                    response.body()?.let { getRecordedResponse ->

                        //既存のリストにあって、レスポンスにないアイテムの削除
                        var horizontalIndex = 0
                        while(horizontalIndex < listRowAdapter.size()) {
                            var found = false
                            getRecordedResponse.recorded.forEach {
                                if(listRowAdapter.get(horizontalIndex).equals(it) ) found = true
                            }
                            if (!found) {
                                listRowAdapter.removeItems(horizontalIndex,1)
                            }else {
                                horizontalIndex += 1
                            }
                        }

                        //レスポンスにあって、既存のリストにないアイテムの追加
                        getRecordedResponse.recorded.forEachIndexed { index, it ->
                            if(listRowAdapter.indexOf(it) == -1){
                                listRowAdapter.add(index,it)
                            }
                        }

                        //続きがあるなら"次を読み込む"を置く。
                        val numOfItem = getRecordedResponse.recorded.count().toLong()
                        if (numOfItem < getRecordedResponse.total) {
                            listRowAdapter.add(GetRecordedParam(limit = v1Pram.limit,
                                offset = numOfItem,
                                reverse = v1Pram.reverse,
                                rule = v1Pram.rule,
                                genre1 = v1Pram.genre1,
                                channel = v1Pram.channel,
                                keyword = v1Pram.keyword,
                                hasTs = v1Pram.hasTs,
                                recording = v1Pram.recording))
                        }

                        // 遅延追加: API 結果を見てから行を追加 or スキップ
                        if (!addedUpfront && getListRowByHeaderId(headerId) == null) {
                            val showEmptyRules = PreferenceManager.getDefaultSharedPreferences(context)
                                .getBoolean(getString(R.string.pref_key_show_empty_rules), true)
                            if (getRecordedResponse.total == 0L && !showEmptyRules) {
                                return@let  // 空ルールは非表示のままスキップ
                            }
                            if (orderedIds != null) {
                                addToCategoryOrdered(category, ListRow(HeaderItem(headerId, title), listRowAdapter), idInCategory, orderedIds)
                            } else {
                                addToCategory(category, ListRow(HeaderItem(headerId, title), listRowAdapter))
                            }
                        }

                        // 録画0件かつ設定がOFFの場合は既存ルール行を非表示にする
                        if (getRecordedResponse.total == 0L && category == Category.RECORDED_BY_RULES) {
                            val showEmptyRules = PreferenceManager.getDefaultSharedPreferences(context)
                                .getBoolean(getString(R.string.pref_key_show_empty_rules), true)
                            if (!showEmptyRules) removeRowFromCategory(category, headerId)
                        }

                    }
                }
                override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                    Log.d(TAG,"loadRows() getRecorded API Failure")
                    Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
                }
            })
            EpgStationV2.api?.getRecorded(
                isHalfWidth = v2Param.isHalfWidth,
                offset = v2Param.offset,
                limit = if(numOfLoaded>v2Param.limit) numOfLoaded else v2Param.limit ,
                isReverse = v2Param.isReverse,
                ruleId = v2Param.ruleId,
                channelId = v2Param.channelId,
                genre = v2Param.genre,
                keyword = v2Param.keyword,
                hasOriginalFile = v2Param.hasOriginalFile )?.enqueue(object : Callback<Records> {
                override fun onResponse(call: Call<Records>, response: Response<Records>) {
                    response.body()?.let { getRecordedResponse ->

                        //既存のリストにあって、レスポンスにないアイテムの削除
                        var horizontalIndex = 0
                        while(horizontalIndex < listRowAdapter.size()) {
                            var found = false
                            getRecordedResponse.records.forEach {
                                if(listRowAdapter.get(horizontalIndex).equals(it) ) found = true
                            }
                            if (!found) {
                                listRowAdapter.removeItems(horizontalIndex,1)
                            }else {
                                horizontalIndex += 1
                            }
                        }

                        //レスポンスにあって、既存のリストにないアイテムの追加
                        getRecordedResponse.records.forEachIndexed { index, it ->
                            if(listRowAdapter.indexOf(it) == -1){
                                listRowAdapter.add(index,it)
                            }
                        }
                        //続きがあるなら"次を読み込む"を置く。
                        val numOfItem = getRecordedResponse.records.count().toLong()
                        if (numOfItem < getRecordedResponse.total) {
                            listRowAdapter.add(GetRecordedParamV2(
                                isHalfWidth = v2Param.isHalfWidth,
                                offset = numOfItem,
                                limit = v2Param.limit,
                                isReverse = v2Param.isReverse,
                                ruleId = v2Param.ruleId,
                                channelId = v2Param.channelId,
                                genre = v2Param.genre,
                                keyword = v2Param.keyword,
                                hasOriginalFile = v2Param.hasOriginalFile))
                        }

                        // 遅延追加: API 結果を見てから行を追加 or スキップ
                        if (!addedUpfront && getListRowByHeaderId(headerId) == null) {
                            val showEmptyRules = PreferenceManager.getDefaultSharedPreferences(context)
                                .getBoolean(getString(R.string.pref_key_show_empty_rules), true)
                            if (getRecordedResponse.total == 0 && !showEmptyRules) {
                                return@let  // 空ルールは非表示のままスキップ
                            }
                            if (orderedIds != null) {
                                addToCategoryOrdered(category, ListRow(HeaderItem(headerId, title), listRowAdapter), idInCategory, orderedIds)
                            } else {
                                addToCategory(category, ListRow(HeaderItem(headerId, title), listRowAdapter))
                            }
                        }

                        // 録画0件かつ設定がOFFの場合は既存ルール行を非表示にする
                        if (getRecordedResponse.total == 0 && category == Category.RECORDED_BY_RULES) {
                            val showEmptyRules = PreferenceManager.getDefaultSharedPreferences(context)
                                .getBoolean(getString(R.string.pref_key_show_empty_rules), true)
                            if (!showEmptyRules) removeRowFromCategory(category, headerId)
                        }

                    }
                }
                override fun onFailure(call: Call<Records>, t: Throwable) {
                    Log.d(TAG,"loadRows() getRecorded API Failure")
                    Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
                }
            })

        }




        /** 録画0件のルール行だけをピンポイントで削除する（他の行を削除しない → フォーカス維持） */
        fun removeEmptyRuleRows() {
            val catOrdinal = Category.RECORDED_BY_RULES.ordinal
            val totalInCat = numOfRowInCategory[catOrdinal]
            if (totalInCat == 0) return
            val headerRows = 2 // DividerRow + SectionRow
            val catStart = numOfRowInCategory.copyOfRange(0, catOrdinal).sum()
            val emptyIds = mutableListOf<Long>()
            for (i in catStart + headerRows until catStart + totalInCat) {
                val row = get(i) as? ListRow ?: continue
                if ((row.adapter as? ArrayObjectAdapter)?.size() == 0) {
                    emptyIds.add(row.headerItem.id)
                }
            }
            emptyIds.forEach { removeRowFromCategory(Category.RECORDED_BY_RULES, it) }
        }

        fun addToCategoryOrdered(cat: Category, item: Any, idInCategory: Long, orderedIds: List<Long>) {
            synchronized(this) {
                if (numOfRowInCategory[cat.ordinal] == 0) {
                    addToCategory(cat, item)
                    return
                }
                val myOrderIndex = orderedIds.indexOf(idInCategory)
                val catStart = numOfRowInCategory.copyOfRange(0, cat.ordinal).sum()
                val headerRows = 2 // DividerRow + SectionRow
                val ruleStart = catStart + headerRows
                val ruleEnd = catStart + numOfRowInCategory[cat.ordinal]
                var insertPos = ruleEnd
                for (i in ruleStart until ruleEnd) {
                    val row = get(i) as? ListRow ?: continue
                    val existingId = row.headerItem.id - cat.ordinal.toLong() * 10000
                    val existingOrderIndex = orderedIds.indexOf(existingId)
                    if (myOrderIndex != -1 && (existingOrderIndex == -1 || existingOrderIndex > myOrderIndex)) {
                        insertPos = i
                        break
                    }
                }
                super.add(insertPos, item)
                numOfRowInCategory[cat.ordinal]++
            }
        }


    }

    // ヘッダーID → アイコンリソースのマップ（負値はSectionRow/Settings用の固定ID）
    private val sidebarIconMap: Map<Long, Int> by lazy {
        mapOf(
            Category.LIVE_CHANNELS.ordinal.toLong() * 10000 to R.drawable.ic_sidebar_live,
            -Category.PROGRAM_NAME_REFRESH.ordinal.toLong() to R.drawable.ic_settings_reload,
            Category.ON_RECORDING.ordinal.toLong() * 10000 to R.drawable.ic_sidebar_rec,
            Category.RECENTLY_RECORDED.ordinal.toLong() * 10000 to R.drawable.ic_sidebar_clock,
            -Category.SEARCH_HISTORY.ordinal.toLong() to R.drawable.ic_sidebar_search,
            -Category.RECORDED_BY_RULES.ordinal.toLong() to R.drawable.ic_sidebar_calendar,
            -Category.SETTINGS.ordinal.toLong() to R.drawable.ic_sidebar_settings
        )
    }

    private open inner class IconRowHeaderPresenter : RowHeaderPresenter() {
        override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
            super.onBindViewHolder(viewHolder, item)
            val row = item as? Row ?: return
            val headerId = row.headerItem?.id ?: return
            val iconResId = sidebarIconMap[headerId]
            val root = viewHolder.view

            // lb_row_header.xml の構造を ID に依存せず子ビューの型で解決する。
            // ケース A: LinearLayout > ImageView (アイコンスロット) + RowHeaderView
            val iconView = (root as? ViewGroup)?.let { vg ->
                (0 until vg.childCount).mapNotNull { vg.getChildAt(it) as? ImageView }.firstOrNull()
            }
            if (iconView != null) {
                if (iconResId != null) {
                    iconView.setImageDrawable(ContextCompat.getDrawable(root.context, iconResId))
                    iconView.visibility = View.VISIBLE
                } else {
                    // INVISIBLE にすることでアイコン幅のスペースを保持し、テキスト開始位置を揃える
                    iconView.visibility = View.INVISIBLE
                }
                return
            }

            // ケース B: ImageView がない場合は TextView の compound drawable に設定
            val textView = (root as? ViewGroup)?.let { vg ->
                (0 until vg.childCount).mapNotNull { vg.getChildAt(it) as? TextView }.firstOrNull()
            } ?: root as? TextView ?: return
            if (iconResId != null) {
                val drawable = ContextCompat.getDrawable(root.context, iconResId)
                val size = textView.textSize.toInt().coerceAtLeast(32)
                drawable?.setBounds(0, 0, size, size)
                textView.setCompoundDrawables(drawable, null, null, null)
                textView.compoundDrawablePadding = size / 3
            } else {
                // 透明プレースホルダーでテキスト開始位置を揃える
                val size = textView.textSize.toInt().coerceAtLeast(32)
                val placeholder = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                placeholder.setBounds(0, 0, size, size)
                textView.setCompoundDrawables(placeholder, null, null, null)
                textView.compoundDrawablePadding = size / 3
            }
        }
    }


    companion object {
        private const val TAG = "MainFragment"

        private const val BACKGROUND_UPDATE_DELAY = 300
        // 設定行の固定カード数（接続設定・プレイヤー設定・表示設定・再読み込み）
        private const val FIXED_SETTINGS_CARD_COUNT = 4
    }


}