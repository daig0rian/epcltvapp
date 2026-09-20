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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
    /**
     * 画面がまだ生きているか。遅れて届いた API 応答を捨てるための門番。
     *
     * ルールの数だけ getRecorded を投げるので、応答が返るまでに秒単位かかることがある。
     * その間に画面を離れてフラグメントが破棄されると、コールバックの中の context / getString が
     * null になって落ちる（実機で発生）。応答を触る前にここで弾く。
     */
    private val isUiAlive: Boolean get() = isAdded

    /**
     * 一度でも [loadRows] で読み込んだか。
     *
     * 起動直後は onCreate の initEPGStationApi → loadRows と、onResume の軽い更新が
     * 二重に走る（同じチャンネル・録画中・最近の録画・履歴を2回取っていた）。
     * 初回は loadRows に任せ、軽い更新は走らせない。
     */
    private var mHasLoadedOnce = false

    /**
     * 再生画面や詳細画面へ移る直前に選んでいた行。
     *
     * 戻ってきたときに Leanback が別の行へ復元してしまうことがある（一覧がまだ空だと、位置が
     * 末尾の設定行へ丸まる）。控えておいて、必要なら選び直す。
     */
    private var mRowIdBeforePause: Long? = null

    /** 画面が作り直されたときに、行が揃ってから選び直したい行。onSaveInstanceState で保存したもの。 */
    private var mPendingRestoreRowId: Long? = null

    /** 選択行を戻すのを待っている最中か。この間だけ利用者操作を見て中止する。 */
    private var mRestorePending = false

    /** 待っている間に利用者が操作したか。操作されたら復元しない。 */
    private var mUserInteractedWhileRestorePending = false

    private var mSettingsRowAdapter: ArrayObjectAdapter? = null

    /** タイトル行の検索ボタン。サイドバーの一番上の行から↑で戻るための参照。 */
    private var mSearchOrb: View? = null

    /**
     * いま表示に使っている「録画ルールの並び」収集器。
     *
     * ルール一覧を取り直すたびに差し替える。前回のロードがまだ飛んでいる間に新しいロードが
     * 始まった場合、古い応答で並べ替えてしまわないよう、収集器側でこの値と自分を照合する。
     */
    private var mActiveRuleOrderCollector: RuleOrderCollector? = null

    private val mCardPresenter = OriginalCardPresenter()
    private val mMainMenuListRowPresenter = ListRowPresenter()
    private val mMainMenuAdapter = MainMenuAdapter(mMainMenuListRowPresenter)

    /** ライブ視聴の番組情報を自動更新するRunnable。固定間隔ではなく、次に終了する番組の終了時刻に合わせて都度スケジュールし直す */
    private val mProgramRefreshRunnable = Runnable { refreshLiveProgramNames() }

    private val mDisplayPrefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        Log.d(TAG, "prefChanged key=$key isResumed=$isResumed adapterSize=${mMainMenuAdapter.size()} selectedPos=$selectedPosition")
        when (key) {
            getString(R.string.pref_key_rules_order_mode) -> {
                // ルールの並び順。並びは取得済みのデータだけで決まるので、行は取り直さずに並べ替える。
                Log.d(TAG, "prefChanged: rules_order_mode → applyRuleOrder (行の再取得なし)")
                applyRuleOrder()
            }
            getString(R.string.pref_key_rules_order_is_newest_first) -> {
                // こちらは検索履歴の並び順。履歴の行だけを作り直す（ルール行の再取得は起こさない）。
                Log.d(TAG, "prefChanged: history_newest_first → refreshSearchHistoryRows")
                val selectedRowId = selectedRowHeaderId()
                refreshSearchHistoryRows()
                restoreSelection(selectedRowId)
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
                // 件数が変わっただけなので、履歴の行だけを作り直す
                Log.d(TAG, "prefChanged: num_of_history → refreshSearchHistoryRows")
                val selectedRowId = selectedRowHeaderId()
                refreshSearchHistoryRows()
                restoreSelection(selectedRowId)
            }
            "pref_key_search_histories" -> {
                Log.d(TAG, "prefChanged: search_histories cleared → refreshSearchHistoryRows")
                val selectedRowId = selectedRowHeaderId()
                refreshSearchHistoryRows()
                restoreSelection(selectedRowId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        // 画面が作り直された場合、Leanback は選んでいた行の「位置」だけを復元する。行がまだ無いと
        // 位置が末尾（設定行）へ丸まり、そのまま居座る。行の id を控えて、行が揃ってから選び直す。
        mPendingRestoreRowId = savedInstanceState
            ?.takeIf { it.containsKey(STATE_SELECTED_ROW_ID) }
            ?.getLong(STATE_SELECTED_ROW_ID)
        Log.i(TAG, "onCreate: 作り直し=${savedInstanceState != null} 復元待ちの行=$mPendingRestoreRowId")

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // タイトル行（検索ボタンがある行）は Leanback が組み立てるので、出来上がってから足す
        view.post { addSettingsButton() }
    }

    override fun onResume() {
        Log.i(TAG, "onResume adapterSize=${mMainMenuAdapter.size()} selectedPos=$selectedPosition 選択行=${selectedRowHeaderId()} 復元待ち=$mPendingRestoreRowId 直前=$mRowIdBeforePause flags[reloadAll=$mNeedsReloadAllOnResume conn=$mNeedsCheckConnectionOnResume hist=$mNeedsReloadHistoryOnResume]")
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
                    // 検索から戻ったときは履歴の行だけを作り直す。以前は updateRows() を呼んでいたため、
                    // ここでも録画ルール全件の getRecorded() が走っていた。
                    Log.d(TAG, "onResume: reloadHistory deferred → refreshSearchHistoryRows adapterSize=${mMainMenuAdapter.size()}")
                    val selectedRowId = selectedRowHeaderId()
                    refreshSearchHistoryRows()
                    restoreSelection(selectedRowId)
                }
            }
            else -> {
                // 録画中・最近の録画・検索履歴だけ取り直す。ルール行はそのまま残す。
                if (!mHasLoadedOnce) {
                    // 起動直後。このあと loadRows が全部読むので、ここで取ると同じものを二度取ることになる。
                    Log.i(TAG, "onResume: 初回は loadRows に任せる（軽い更新はしない）")
                } else {
                    Log.i(TAG, "onResume: branch=else → 軽い更新（ルール行は触らない）")
                    updateRows(includeRules = false)
                }
            }
        }
        // 表示中のみ動かすため画面を離れたら止める。ポーズ中に終了時刻を迎えた番組があるかもしれないので、
        // 再開時は都度スケジュールし直すのではなく、最新情報を取り直してから次のタイマーを仕掛け直す。
        refreshLiveProgramNames()
        // Leanback の復元は onResume より後（レイアウト時）なので、少し待ってから選択行を見る。
        scheduleSelectionRestore()
    }

    override fun onPause() {
        super.onPause()
        mHandler.removeCallbacks(mProgramRefreshRunnable)
        mRowIdBeforePause = selectedRowHeaderId()
        Log.i(TAG, "onPause: adapterSize=${mMainMenuAdapter.size()} selectedPos=$selectedPosition 選択行=$mRowIdBeforePause")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 画面が作り直されても、選んでいた行へ戻せるように控える。
        selectedRowHeaderId()?.let { outState.putLong(STATE_SELECTED_ROW_ID, it) }
        Log.i(TAG, "onSaveInstanceState: 選択行=${selectedRowHeaderId()} adapterSize=${mMainMenuAdapter.size()}")
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
        //Preferenceに設定されている接続先でAPIをセットアップする。
        //接続先の解決とバージョン判定は EpgStationApiInitializer が持つ。deep link の
        //受け口(DeepLinkActivity)が同じ初期化を必要とするため共通化してある。
        //一覧の取得はここに残す——画面によって要るものが違うため。
        EpgStationApiInitializer.initialize(requireContext()) { result ->
            // 接続先が応答しないと初期化は数秒かかる。画面を離れた後に返ってきた場合は何もしない。
            if (!isUiAlive) return@initialize
            when (result) {
                is EpgStationApiInitializer.Result.V2 -> {
                    EpgStationV2.fetchChannels()
                    EpgStationV2.fetchStreamConfig()
                }

                is EpgStationApiInitializer.Result.V1 -> {
                    EpgStation.fetchChannels()
                }

                is EpgStationApiInitializer.Result.Failed -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.connect_epgstation_failed) + "\n" + result.detail,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
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
            private val sectionPresenter = object : IconRowHeaderPresenter() {
                override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
                    return super.onCreateViewHolder(parent).also { vh ->
                        vh.view.isFocusable = false
                        vh.view.isFocusableInTouchMode = false
                    }
                }
            }

            /**
             * サイドバーの区切り線。
             *
             * Leanback は「選択されたヘッダー」の ViewHolder を RowHeaderPresenter.ViewHolder として
             * 扱う（HeadersSupportFragment.onRowSelected）。素の DividerPresenter は汎用の
             * Presenter.ViewHolder を返すため、区切り行が選ばれた瞬間に ClassCastException で落ちる
             * （実機で発生。行が増減する読み込み中に起きやすい）。
             *
             * さらに RowHeaderPresenter.ViewHolder の生成時には、渡された view 配下の
             * R.id.row_header が RowHeaderView であることが要求される。leanback の lb_divider.xml は
             * 根が素の View なので、その view をそのまま包むと今度はそこで ClassCastException になる
             * （実機で発生）。そのため区切り線の見た目はそのまま、根を RowHeaderView にした
             * R.layout.sidebar_divider を使う。
             *
             * レイアウトを差し替える RowHeaderPresenter(int) は @RestrictTo(LIBRARY_GROUP) のため
             * lint が RestrictedApi として咎めるが、上記のキャストを通すにはこの経路しかない。
             */
            @SuppressLint("RestrictedApi")
            private val dividerPresenter = object : RowHeaderPresenter(R.layout.sidebar_divider) {
                override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
                    // 区切り線なので何も表示しない
                }

                override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
                    // 同上
                }

                override fun onSelectLevelChanged(viewHolder: RowHeaderPresenter.ViewHolder) {
                    // 既定の実装は選択の度合いに応じて view の alpha を変える
                    // （未選択時は lb_browse_header_unselect_alpha まで薄くなる）。区切り線は
                    // 選択状態によらず同じ濃さにしたいので何もしない。
                }
            }

            override fun getPresenter(item: Any?): Presenter = when (item) {
                is DividerRow -> dividerPresenter
                is SectionRow -> sectionPresenter
                else -> iconPresenter
            }
        })
    }

    /**
     * 各行を読み込む。
     *
     * @param includeRules 録画ルールの行も取り直すか。画面に戻ってきただけのときは false にする——
     *        ルールが1125件ある環境では全件の取り直しに1分近くかかり、その間ずっと「読み込み中」になるため。
     *        ルールの録画を取り直したいときは設定の「録画の再読み込み」（reloadContentRows）を使う。
     */
    private fun updateRows(includeRules: Boolean = true) {

        EpgStationV2.api?.let { api ->
            // EPGStation V2.x.x　の場合だけ「ライブ視聴」列を作る

            val liveHeaderId = Category.LIVE_CHANNELS.ordinal.toLong()*10000

            api.getChannels().enqueue(object : Callback<List<ChannelItem>> {
                override fun onResponse(call: Call<List<ChannelItem>>, response: Response<List<ChannelItem>>) {
                    if (!isUiAlive) return
                    response.body()?.let { rawChannels ->
                        // データ放送専用サービス等（映像・音声を伴わないチャンネル）を除外する
                        val channels = rawChannels.filter { ChannelItem.isAudioVideoService(it.type) }
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

                            // チャンネル一覧の取得だけでは番組名(currentProgramName)は入らないため、直後に取得する
                            refreshLiveProgramNames()
                        }
                    }
                }
                override fun onFailure(call: Call<List<ChannelItem>>, t: Throwable) {
                    if (!isUiAlive) return
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
                    if (!isUiAlive) return
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
                    if (!isUiAlive) return
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


        //履歴行の追加。並び順は既存キー（履歴専用）を見る。
        refreshSearchHistoryRows()

        // 画面に戻っただけのときはここで止める。ルール行は前回の内容のまま残す。
        if (!includeRules) {
            Log.i(TAG, "updateRows: ルール行は取り直さない（戻ってきただけ）")
            return
        }

        //ルール一覧の並び順。既定は「ルールの新しい順」。
        val ruleSortMode = currentRuleSortMode()

        //次の横の列。録画ルール。録画ルールの数だけ行が増える。
        EpgStation.api?.getRulesList()?.enqueue(object : Callback<List<RuleList>> {
            override fun onResponse(call: Call<List<RuleList>>, response: Response<List<RuleList>>) {
                if (!isUiAlive) return
                response.body()?.let{ it ->
                    // 受け取った順のまま持ち、表示順は RuleOrder に決めさせる。
                    val ruleIdsInServerOrder = it.map { rule -> rule.id.toLong() }
                    // 最新録画日時を集める収集器。下ごしらえの結果もここへ入れ、1ルール分の応答で上書きする。
                    val ruleOrder = RuleOrderCollector(ruleIdsInServerOrder)
                    mActiveRuleOrderCollector = ruleOrder

                    // 行を足していく処理。足す順も orderedIds に合わせる
                    //（行を足すと同時にそのルールの録画を取りに行くので、上に来るルールの録画が先に届く）
                    val ruleById = it.associateBy { rule -> rule.id }
                    val addRows: (List<Long>) -> Unit = { orderedIds ->
                        addRuleRowsChunked(orderedIds) { ruleId ->
                            val rule = ruleById[ruleId]
                            if (rule == null) {
                                Log.i(TAG, "addRows: ルール $ruleId の定義が見つからないので飛ばす")
                                return@addRuleRowsChunked
                            }

                            //録画ルールにキーワードが設定されていない場合、キーワードの代わりにルールIDをセット
                            val keyword:String = if ( rule.keyword.isNullOrEmpty() ){
                                getString(R.string.rule_id_is_x, rule.id.toString())
                            }else{
                                rule.keyword
                            }
                            mMainMenuAdapter.updateContentsListRowWithCategory(
                                GetRecordedParam(rule = rule.id, limit = RULE_ROW_INITIAL_LIMIT),
                                GetRecordedParamV2(ruleId = rule.id, limit = RULE_ROW_INITIAL_LIMIT),
                                keyword,
                                Category.RECORDED_BY_RULES,
                                rule.id,
                                orderedIds,
                                ruleOrder
                            )
                        }
                    }

                    if (ruleSortMode == RuleOrder.MODE_RECORDING_NEWEST) {
                        // v1 も同じ下ごしらえを使う。1ルール1回の取得を待たずに上位の並びを確定させる。
                        // 行を足すのは1ページ目が返った時点。2ページ目以降は裏で読み続けて並べ替えの材料に足す。
                        var rowsAdded = false
                        fetchLatestRecordedSeed { seed ->
                            ruleOrder.seedRecordedAt(seed)
                            if (!rowsAdded) {
                                rowsAdded = true
                                addRows(ruleOrder.orderedRuleIds(ruleSortMode))
                            }
                        }
                    } else {
                        addRows(RuleOrder.provisionalOrder(ruleSortMode, ruleIdsInServerOrder))
                    }
                }
            }
            override fun onFailure(call: Call<List<RuleList>>, t: Throwable) {
                if (!isUiAlive) return
                Log.d(TAG,"loadRows() getRulesList API Failure")
                Toast.makeText(context!!, R.string.connect_epgstation_failed, Toast.LENGTH_LONG).show()
            }
        })
        EpgStationV2.api?.getRules(limit=Int.MAX_VALUE)?.enqueue(object : Callback<Rules> {
            override fun onResponse(call: Call<Rules>, response: Response<Rules>) {
                if (!isUiAlive) return
                response.body()?.rules?.let{ rules ->
                    // EPGStation は rule.id の昇順で返す。受け取った順のまま持ち、表示順は RuleOrder に決めさせる。
                    val ruleIdsInServerOrder = rules.map { rule -> rule.id.toLong() }
                    // 最新録画日時を集める収集器。下ごしらえの結果もここへ入れ、1ルール分の応答で上書きする。
                    val ruleOrder = RuleOrderCollector(ruleIdsInServerOrder)
                    mActiveRuleOrderCollector = ruleOrder

                    // 行を足していく処理。orderedIds は行を足すときの並び。
                    // 足す順も orderedIds に合わせる。行を足すと同時にそのルールの録画を取りに行くので、
                    // 上に来るルール（最近録画されたもの）の録画が先に届き、開いてすぐ見られる。
                    val ruleById = rules.associateBy { rule -> rule.id }
                    val addRows: (List<Long>) -> Unit = { orderedIds ->
                        // 一度に1125行を足すと main スレッドが数秒占有されて画面が固まる。
                        // 少しずつ足して main ループに戻し、上の列から先に表示・取得されるようにする。
                        addRuleRowsChunked(orderedIds) { ruleId ->
                            val rule = ruleById[ruleId]
                            if (rule == null) {
                                Log.i(TAG, "addRows: ルール $ruleId の定義が見つからないので飛ばす")
                                return@addRuleRowsChunked
                            }

                            //録画ルールにキーワードが設定されていない場合、キーワードの代わりにルールIDをセット
                            val keyword:String = if ( rule.searchOption?.keyword.isNullOrEmpty() ){
                                getString(R.string.rule_id_is_x, rule.id.toString())
                            }else{
                                rule.searchOption?.keyword!!
                            }
                            mMainMenuAdapter.updateContentsListRowWithCategory(
                                GetRecordedParam(rule = rule.id, limit = RULE_ROW_INITIAL_LIMIT),
                                GetRecordedParamV2(ruleId = rule.id, limit = RULE_ROW_INITIAL_LIMIT),
                                keyword,
                                Category.RECORDED_BY_RULES,
                                rule.id,
                                orderedIds,
                                ruleOrder
                            )
                        }
                    }

                    if (ruleSortMode == RuleOrder.MODE_RECORDING_NEWEST) {
                        // 1ルール1回の取得を待たずに上位の並びを確定させるため、先に下ごしらえを読む。
                        // 失敗しても seed は空のまま返ってくるので、従来どおり仮の並びで行を足す。
                        // 行を足すのは1ページ目が返った時点。2ページ目以降は裏で読み続けて並べ替えの材料に足す。
                        var rowsAdded = false
                        fetchLatestRecordedSeed { seed ->
                            ruleOrder.seedRecordedAt(seed)
                            if (!rowsAdded) {
                                rowsAdded = true
                                addRows(ruleOrder.orderedRuleIds(ruleSortMode))
                            }
                        }
                    } else {
                        addRows(RuleOrder.provisionalOrder(ruleSortMode, ruleIdsInServerOrder))
                    }
                }
            }
            override fun onFailure(call: Call<Rules>, t: Throwable) {
                if (!isUiAlive) return
                Log.d(TAG,"loadRows() getRulesList API Failure")
                Toast.makeText(context!!, R.string.connect_epgstation_failed, Toast.LENGTH_LONG).show()
            }
        })

    }

    private fun loadRows() {
        mHasLoadedOnce = true

        //内容クリア
        mMainMenuAdapter.clear()

        //コンテンツをロード。
        updateRows()

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
        // アップデート確認。カードの見た目は状態によらず常に同じで、押したときだけ確認しに行く。
        // 起動時チェックは行わない (AppUpdateDialogFragment の KDoc を参照)。
        gridRowAdapter.add(SettingsCardPresenter.Item(
            R.drawable.ic_settings_update,
            getString(R.string.settings_update),
            SettingsCardPresenter.Item.Action.UPDATE
        ))

        mMainMenuAdapter.addToCategory(Category.SETTINGS, ListRow(gridHeader, gridRowAdapter))



    }


    /** 設定行を保持したまま、コンテンツ行だけをクリアして再読み込みする */
    private fun reloadContentRows() {
        listOf(Category.LIVE_CHANNELS, Category.ON_RECORDING, Category.RECENTLY_RECORDED, Category.SEARCH_HISTORY, Category.RECORDED_BY_RULES)
            .forEach { mMainMenuAdapter.deleteCategory(it) }
        updateRows()
    }

    /**
     * ルール行を ids の順に、少しずつ足す。
     *
     * 1125件を一度に足すと、行の生成と1ルール分の取得依頼だけで main スレッドが数秒占有され、
     * その間は画面がまったく更新されない（上の方の列の録画も出てこない）。
     * 小さく区切って main ループに戻すことで、上の列から先に表示・取得される。
     */
    private fun addRuleRowsChunked(ids: List<Long>, addOne: (Long) -> Unit) {
        var index = 0
        val step = object : Runnable {
            override fun run() {
                // 画面から離れた後に続きを足さない
                if (!isUiAlive) return
                val end = minOf(index + RULE_ROW_CHUNK_SIZE, ids.size)
                while (index < end) {
                    addOne(ids[index])
                    index++
                }
                // 続きは少し間を空けて頼む。1チャンクの仕事でフレームを落としたぶんを、
                // 次のフレームに返してやる（そのままだと 1126 行を作る間ずっと引っかかる）。
                if (index < ids.size) mHandler.postDelayed(this, RULE_ROW_CHUNK_INTERVAL_MS)
            }
        }
        step.run()
    }

    /**
     * 録画ルール行を、いま設定されている並び順へ並べ直す。
     *
     * [RuleOrderCollector] が全ルール分の応答を受け取ったときに加えて、並び順の設定を変えたときにも
     * 呼ぶ。どちらも既に手元にあるデータだけで並びが決まるので、ここで通信は起きない。応答が1件
     * 返るたびに並べ直すと600件の環境では一覧全体の再配置が繰り返し走るため、確定はこの1回に絞る。
     * 並びが既に目標と同じときは何もしない（並べ替えのための再描画も起きない）。
     */
    private fun applyRuleOrder() {
        val collector = mActiveRuleOrderCollector ?: return
        val mode = currentRuleSortMode()
        val orderedIds = collector.orderedRuleIds(mode)
        // 並べ替えで行が動いても、いま選んでいる行が別のルールにすり替わらないよう控えておく
        val selectedRowId = selectedRowHeaderId()
        val changed = mMainMenuAdapter.reorderCategory(Category.RECORDED_BY_RULES, orderedIds)
        // 実機で並びを確かめられるように、確定した先頭と末尾だけ残す（番組名は出さない）
        Log.i(TAG, "applyRuleOrder: mode=$mode 並べ替え=$changed 先頭=${orderedIds.take(5).map { it to collector.latestRecordedAtOf(it) }} 末尾=${orderedIds.takeLast(3).map { it to collector.latestRecordedAtOf(it) }}")
        if (changed) {
            restoreSelection(selectedRowId)
        }
    }

    /** 設定に保存されているルール一覧の並び順。未設定・不正値は既定へ倒れる。 */
    private fun currentRuleSortMode(): String = RuleOrder.modeFromPreference(
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(getString(R.string.pref_key_rules_order_mode), null)
    )

    /** 検索履歴の並び順（既存キーの真偽値）。true で新しい順。 */
    private fun isHistoryNewestFirst(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(getString(R.string.pref_key_rules_order_is_newest_first), false)

    /** いま選んでいる行の headerId。並べ替えや作り直しの前後で選択を保つために控える。 */
    private fun selectedRowHeaderId(): Long? =
        if (selectedPosition in 0 until mMainMenuAdapter.size()) {
            (mMainMenuAdapter.get(selectedPosition) as? ListRow)?.headerItem?.id
        } else {
            null
        }

    /** 控えておいた行が、作り直した後も同じ行として選ばれるように選択位置を合わせ直す。 */
    private fun restoreSelection(headerId: Long?) {
        if (headerId == null) return
        val newPosition = mMainMenuAdapter.indexOfListRowByHeaderId(headerId)
        if (newPosition >= 0 && newPosition != selectedPosition) {
            // 同じ行を選んだままにするだけ。先頭へ飛ばしたりスクロール位置を戻したりはしない。
            setSelectedPosition(newPosition, false)
        }
    }

    /**
     * 画面に戻ってきたとき、Leanback が選んでいた行とは違う行へ復元してしまうのを戻す。
     *
     * 復元は「行の位置」で行われるため、一覧がまだ空だと位置が別の行（最後に足された行＝設定行など）へ
     * 丸まり、そこに居座る。控えておいた行が現れるのを待って選び直す。
     *
     * 待っている間に利用者が自分で動かしたら、そちらを優先して復元はやめる（[onUserInteractionByUser]）。
     */
    private fun scheduleSelectionRestore() {
        val want = mPendingRestoreRowId ?: mRowIdBeforePause ?: return
        mPendingRestoreRowId = null
        mRestorePending = true
        mUserInteractedWhileRestorePending = false
        var tries = 0
        val step = object : Runnable {
            override fun run() {
                if (!isUiAlive) {
                    mRestorePending = false
                    return
                }
                if (mUserInteractedWhileRestorePending) {
                    mRestorePending = false
                    Log.i(TAG, "選択行を戻すのをやめる: 利用者が操作した（控え=$want）")
                    return
                }
                val current = selectedRowHeaderId()
                if (current == want) {
                    mRestorePending = false
                    return
                }
                val position = mMainMenuAdapter.indexOfListRowByHeaderId(want)
                if (position >= 0) {
                    mRestorePending = false
                    Log.i(TAG, "選択行を戻す: 行=$want 位置=$position （直前=$current 試行=$tries）")
                    setSelectedPosition(position, false)
                    return
                }
                if (tries < SELECTION_RESTORE_MAX_TRIES) {
                    tries++
                    mHandler.postDelayed(this, SELECTION_RESTORE_RETRY_MS)
                } else {
                    mRestorePending = false
                    Log.i(TAG, "選択行を戻せなかった: 行=$want が現れない（adapterSize=${mMainMenuAdapter.size()}）")
                }
            }
        }
        mHandler.postDelayed(step, SELECTION_RESTORE_RETRY_MS)
    }

    /** [scheduleSelectionRestore] が待っている間に、利用者が自分で操作したことを伝える。 */
    fun onUserInteractionByUser() {
        if (mRestorePending) mUserInteractedWhileRestorePending = true
    }

    /**
     * 検索履歴の行だけを作り直す。
     *
     * 以前は履歴に関わる設定が変わるたびに updateRows() を呼んでいた。updateRows() は全カテゴリを
     * 作り直すため、履歴の並びを変えただけで録画ルール全件の getRecorded() も走っていた
     * （600件の環境なら600リクエスト）。ここでは履歴カテゴリしか触らない。
     */
    private fun refreshSearchHistoryRows() {
        mMainMenuAdapter.deleteCategory(Category.SEARCH_HISTORY)

        val historyList = SearchFragment.getHistory(requireContext())
        val orderedHistory = if (isHistoryNewestFirst()) historyList.asReversed() else historyList
        orderedHistory.forEachIndexed { index, it ->
            mMainMenuAdapter.updateContentsListRowWithCategory(
                GetRecordedParam(keyword = it),
                GetRecordedParamV2(keyword = it),
                it,
                Category.SEARCH_HISTORY,
                index.toLong()
            )
        }
    }

    /**
     * 「録画の新しい順」の下ごしらえ。
     *
     * `/api/recorded` を ruleId なし・startAt 降順で数ページ読み、ruleId → 最後に録画された startAt を作る。
     * 行を作るために元から走る1ルール1回の取得が全部返るのを待たず、上位の並びを先に確定させるためのもの。
     * ページ数と1ページの件数はここで上限を切る（サーバーへの負荷を増やしすぎないため）。
     *
     * 全ルールを覆えないこともある（録画が少ないルールは深いページにしか出てこない）。覆えなかったルールは、
     * あとから届く1ルール分の応答で埋まる。
     *
     * @param onReady ページが1枚返るたびに呼ぶ。1ページ目で行の追加を始められるようにするためで、
     *        失敗したときも必ず一度は呼ぶ（呼ばれないと待っている側が動き出せない）。
     */
    private fun fetchLatestRecordedSeed(onReady: (Map<Long, Long>) -> Unit) {
        val seed = HashMap<Long, Long>()

        /** 1ページ取り、(ruleId, startAt) の組と「ページが埋まっていたか」を返す。 */
        fun requestPage(page: Int, onPage: (List<Pair<Long, Long>>, Boolean) -> Unit) {
            val offset = page.toLong() * AGGREGATE_PAGE_LIMIT
            val limit = AGGREGATE_PAGE_LIMIT.toLong()

            val apiV2 = EpgStationV2.api
            if (apiV2 != null) {
                apiV2.getRecorded(isHalfWidth = true, offset = offset, limit = limit, isReverse = false)
                    .enqueue(object : Callback<Records> {
                        override fun onResponse(call: Call<Records>, response: Response<Records>) {
                            if (!isUiAlive) return
                            val records = response.body()?.records.orEmpty()
                            onPage(
                                records.mapNotNull { r -> r.ruleId?.let { id -> id to r.startAt } },
                                records.size >= AGGREGATE_PAGE_LIMIT
                            )
                        }

                        override fun onFailure(call: Call<Records>, t: Throwable) {
                            Log.i(TAG, "ruleOrderSeed: ${page + 1}ページ目で失敗 ${t.javaClass.simpleName}")
                            if (isUiAlive) onPage(emptyList(), false)
                        }
                    })
                return
            }

            // EPGStation v1 も /api/recorded の形が違うだけで考え方は同じ
            val apiV1 = EpgStation.api
            if (apiV1 == null) {
                onPage(emptyList(), false)
                return
            }
            apiV1.getRecorded(limit = limit, offset = offset, reverse = false)
                .enqueue(object : Callback<GetRecordedResponse> {
                    override fun onResponse(call: Call<GetRecordedResponse>, response: Response<GetRecordedResponse>) {
                        if (!isUiAlive) return
                        val records = response.body()?.recorded.orEmpty()
                        onPage(
                            records.mapNotNull { r -> r.ruleId?.let { id -> id to r.startAt } },
                            records.size >= AGGREGATE_PAGE_LIMIT
                        )
                    }

                    override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                        Log.i(TAG, "ruleOrderSeed: ${page + 1}ページ目で失敗 ${t.javaClass.simpleName}")
                        if (isUiAlive) onPage(emptyList(), false)
                    }
                })
        }

        fun fetchPage(page: Int) {
            requestPage(page) { pairs, pageFull ->
                // startAt の降順で返るので、まだ知らないルールにとっての最初の1件がそのルールの最新
                pairs.forEach { (ruleId, startAt) -> if (!seed.containsKey(ruleId)) seed[ruleId] = startAt }
                Log.i(TAG, "ruleOrderSeed: ${page + 1}ページ目 ${pairs.size}件 累計ルール=${seed.size}")
                // 1ページ目が返った時点で呼び出し側へ渡す。ここで行の追加とそのルールの録画取得を始めさせ、
                // 残りのページは裏で読み続けて、確定時の並べ替えの材料にする（表示を待たせない）。
                onReady(seed)
                // ページが埋まっていて、上限にも達していなければ次のページを読む
                if (pageFull && page + 1 < AGGREGATE_MAX_PAGES) fetchPage(page + 1)
            }
        }

        fetchPage(0)
    }

    /**
     * 録画ルール一覧の並びを決めるための、1ロード分の収集状態。
     *
     * 各行の内容を作るために元から走っている `getRecorded(ruleId = ...)` の応答から
     * 「そのルールで最後に録画された番組の startAt」を集めるだけで、並べ替えのための追加の
     * API 呼び出しは行わない。応答が1件返るたびに並べ替えるのではなく、全ルール分が揃ってから
     * [applyRuleOrder] を1回だけ呼ぶ。
     */
    private inner class RuleOrderCollector(private val ruleIdsInServerOrder: List<Long>) {

        /** ruleId → そのルールで最後に録画された番組の startAt (ms)。録画実績がないルールは入らない。 */
        private val latestRecordedAt = HashMap<Long, Long>()

        /** 応答が返ってきた ruleId。同じルールを二重に数えないためのもの。 */
        private val reportedRuleIds = HashSet<Long>()

        private var settled = false

        /**
         * ルール1件分の getRecorded が完了したときに呼ぶ。成功・失敗のどちらの経路でも必ず1回だけ
         * 呼ぶこと。呼ばれないルールがあると一覧の並びが確定しない。
         */
        fun report(ruleId: Long, latestStartAt: Long?) {
            // 新しいロードが始まっていたら、遅れて返ってきた古い応答は捨てる
            if (settled || this !== mActiveRuleOrderCollector) return

            if (latestStartAt != null) latestRecordedAt[ruleId] = latestStartAt
            reportedRuleIds.add(ruleId)
            if (reportedRuleIds.size % RULE_LOAD_LOG_INTERVAL == 0) {
                Log.i(TAG, "ruleOrder: ${reportedRuleIds.size}/${ruleIdsInServerOrder.size} 件の応答を回収")
            }
            if (reportedRuleIds.size < ruleIdsInServerOrder.size) return

            Log.i(TAG, "ruleOrder: ${ruleIdsInServerOrder.size} 件すべての応答が揃った（録画実績あり=${latestRecordedAt.size}件）")
            settled = true
            applyRuleOrder()
        }

        /** 指定された並び順に並べたルール ID の一覧。 */
        fun orderedRuleIds(mode: String): List<Long> =
            RuleOrder.orderedRuleIds(mode, ruleIdsInServerOrder, latestRecordedAt)

        /** 下ごしらえで分かった ruleId → 最新 startAt を取り込む。あとから届く1ルール分の応答が上書きする。 */
        fun seedRecordedAt(seed: Map<Long, Long>) {
            latestRecordedAt.putAll(seed)
        }

        /** 診断ログ用。この ruleId の最終録画日時。録画実績が無ければ null。 */
        fun latestRecordedAtOf(ruleId: Long): Long? = latestRecordedAt[ruleId]
    }

    /**
     * 現在放送中の番組情報をまとめて取り直し、実際に番組が切り替わったカードだけを再描画する。
     * 次回の実行は、表示中チャンネルの中で最も早く終了する番組の終了時刻に合わせてスケジュールする
     * （固定間隔で全カードを再描画するとチラつくため、カード単位の終了時刻ベースの更新に変更）。
     */
    private fun refreshLiveProgramNames() {
        val headerId = Category.LIVE_CHANNELS.ordinal.toLong()*10000
        if (mMainMenuAdapter.getListRowByHeaderId(headerId) == null) return

        EpgStationV2.api?.getScheduleOnAir()?.enqueue(object : Callback<List<Schedule>> {
            override fun onResponse(call: Call<List<Schedule>>, response: Response<List<Schedule>>) {
                if (!isUiAlive) return
                val programByChannelId = response.body()
                    ?.associate { it.channel.id to it.programs.firstOrNull() }
                    ?: return
                // レスポンス到達までの間に行のアダプタが再生成されている可能性があるため、反映直前に取り直す
                val adapter = (mMainMenuAdapter.getListRowByHeaderId(headerId)?.adapter as? ArrayObjectAdapter) ?: return

                val changedIndices = mutableListOf<Int>()
                var nextProgramEndAt = Long.MAX_VALUE
                for (i in 0 until adapter.size()) {
                    val channelItem = adapter.get(i) as? ChannelItem ?: continue
                    val program = programByChannelId[channelItem.id]
                    if (channelItem.currentProgramStartAt != program?.startAt) {
                        channelItem.currentProgramName = program?.name
                        channelItem.currentProgramStartAt = program?.startAt
                        channelItem.currentProgramEndAt = program?.endAt
                        changedIndices.add(i)
                    }
                    program?.endAt?.let { endAt -> if (endAt < nextProgramEndAt) nextProgramEndAt = endAt }
                }

                activity?.runOnUiThread {
                    // 番組が切り替わったカードだけを再描画する（他のカードはチラつかせない）
                    changedIndices.forEach { adapter.notifyArrayItemRangeChanged(it, 1) }
                }

                scheduleNextProgramRefresh(nextProgramEndAt)
            }
            override fun onFailure(call: Call<List<Schedule>>, t: Throwable) {
                if (!isUiAlive) return
                Log.d(TAG,"refreshLiveProgramNames() getScheduleOnAir API Failure")
                // 失敗時もフォールバック間隔でリトライする
                scheduleNextProgramRefresh(Long.MAX_VALUE)
            }
        })
    }

    /** 次に番組が終了する時刻（不明な場合は Long.MAX_VALUE）に合わせて自動更新タイマーを仕掛け直す */
    private fun scheduleNextProgramRefresh(nextProgramEndAt: Long) {
        val delay = if (nextProgramEndAt == Long.MAX_VALUE) {
            PROGRAM_REFRESH_FALLBACK_INTERVAL_MS
        } else {
            (nextProgramEndAt - System.currentTimeMillis() + PROGRAM_END_REFRESH_BUFFER_MS)
                .coerceAtLeast(MIN_PROGRAM_REFRESH_DELAY_MS)
        }
        mHandler.removeCallbacks(mProgramRefreshRunnable)
        mHandler.postDelayed(mProgramRefreshRunnable, delay)
    }

    /** USBデバッグなしでもクラッシュ内容を確認できるよう、前回起動時のクラッシュログがあれば表示する */
    private fun showPreviousCrashIfAny() {
        val crashFile = java.io.File(requireContext().filesDir, EpgTvApplication.CRASH_LOG_FILENAME)
        if (!crashFile.exists()) return
        val content = try { crashFile.readText() } catch (_: Exception) { "" }
        crashFile.delete()
        if (content.isBlank()) return
        androidx.appcompat.app.AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_MinWidth)
            .setTitle(getString(R.string.previous_crash_title))
            .setMessage(content)
            .setPositiveButton(getString(R.string.close)) { _, _ -> }
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

    /**
     * タイトル行の検索ボタンの右隣へ、同じ見た目の設定ボタン（歯車）を足す。
     *
     * サイドバーの一番下の「設定」まで行かなくても設定画面へ入れるようにするためのもの。
     * 検索ボタンと同じ [SearchOrbView] を使うので大きさとフォーカス時の見え方が揃い、
     * D-pad では検索ボタンから右へ移るだけになる。サイドバーの一覧や行の持ち方には触らない。
     */
    private fun addSettingsButton() {
        if (!isAdded) return
        // タイトル行はテーマで差し替えられることがあるので、期待した型でなければ何もしない
        val titleBar = getTitleView() as? TitleView ?: return
        // 画面を作り直したときに二重に足さない
        if (titleBar.findViewWithTag<View>(SETTINGS_BUTTON_TAG) != null) return
        val searchOrb = titleBar.searchAffordanceView as? SearchOrbView ?: return

        val gearOrb = SearchOrbView(requireContext()).apply {
            id = R.id.epg_settings_orb
            tag = SETTINGS_BUTTON_TAG
            contentDescription = getString(R.string.settings)
            searchOrb.orbColors?.let { setOrbColors(it) }
            setOrbIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_sidebar_settings))
            setOnOrbClickedListener { openSettingsScreen() }
            // アイコンの ImageView にはレイアウト由来の「検索」の説明が入っている。
            // 歯車ボタンの説明は親の FrameLayout が持っているので、二重に読まれないよう消す。
            firstImageView(this)?.contentDescription = null
        }
        titleBar.addView(
            gearOrb,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START
            )
        )

        // 横並びの2つのボタンは、座標任せにせずキーで直接行き来させる。
        // nextFocusRightId / nextFocusLeftId はこの画面では効かなかった（実機で確認。
        // 検索ボタンは Leanback が表示を切り替えるため、座標による探索の対象から外れることがある）。
        // （OnKeyListener は、そのビュー自身にフォーカスがあるときだけ呼ばれる。子は持たないので取り違えない）
        searchOrb.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                // タイトル行の上には何も無い。既定の探索だと横の設定ボタンへ飛んでしまうので動かさない
                KeyEvent.KEYCODE_DPAD_UP -> true
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 設定ボタンはサイドバーが出ているときだけ出している。隠れているときは動かさない
                    if (gearOrb.isFocusable) {
                        Log.i(TAG, "タイトル行: 検索ボタンの→で設定ボタンへ")
                        gearOrb.requestFocus()
                    }
                    true
                }
                else -> false
            }
        }
        gearOrb.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> true
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    Log.i(TAG, "タイトル行: 設定ボタンの←で検索ボタンへ")
                    searchOrb.requestFocus()
                    true
                }
                else -> false
            }
        }

        // 置き場所は検索ボタンの実測値から決める。余白はテーマ任せなので決め打ちしない。
        // gravity=start の子の left は「親の padding + 自分の margin」なので、親の padding ぶんを
        // 引いてから margin に入れる。
        // オーブはフォーカスで少し大きくなるので、右端は「実測した最大値」を使う。
        // そのままだと検索ボタンを選ぶたびに設定ボタンの位置が動いてしまう。
        val gap = (SETTINGS_BUTTON_GAP_DP * resources.displayMetrics.density).toInt()
        var maxOrbRight = 0
        val alignNextToSearchOrb = Runnable {
            // 検索ボタンが隠れる状態では設定ボタンも一緒に隠す。
            // Leanback はタイトル行を残したまま検索ボタンだけを GONE にすることがある
            // （updateComponentsVisibility / updateSearchOrbViewVisiblity）。歯車だけ残ると不自然なので合わせる。
            if (gearOrb.visibility != searchOrb.visibility) {
                gearOrb.visibility = searchOrb.visibility
            }
            val params = gearOrb.layoutParams as? FrameLayout.LayoutParams ?: return@Runnable
            if (searchOrb.width == 0) return@Runnable
            val right = searchOrb.left + searchOrb.width
            if (right > maxOrbRight) maxOrbRight = right
            val left = (maxOrbRight - titleBar.paddingLeft) + gap
            if (params.leftMargin != left) {
                params.leftMargin = left
                gearOrb.layoutParams = params
            }
        }
        // 画面の回転やテーマ変更で検索ボタンの位置が変わっても追従させる
        searchOrb.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignNextToSearchOrb.run() }
        alignNextToSearchOrb.run()

        // 設定ボタンの出し入れ。
        //   出す   : 検索ボタンの右隣（サイドバーの帯がその位置まで来ているときだけ）
        //   隠す   : 検索ボタンの裏へ回り込んで透明（閉じたときにスッと隠れる）
        //
        // 起動直後はタイトル行がまだフェードイン中で、歯車だけが先に描かれて内容の上に浮いていた。
        // また Leanback はタイトル行もサイドバーもアニメーションで出すため、サイドバーを畳んだ直後に
        // 状態が一瞬揺れ、「隠れる → うっすら出る → また隠れる」ように見えていた。
        // そこで (1) 検索ボタンが実際に表示されるまでは出さない、
        //        (2) 出す条件は少しの間続いてから効かせる（隠すのは即）、
        //        (3) 隠れている間は毎フレーム隠し直す、の3つで揺れを吸収する。
        var headersRoot: View? = getHeadersSupportFragment()?.view
        var settingsShown = false
        var hiding = false
        var wantedSince = 0L
        /** 検索ボタンが一度きちんと表示されたか。表示される前は設定ボタンを出さない。 */
        var searchOrbReady = false
        val applySettingsButtonState = object : Runnable {
            override fun run() {
                if (!isAdded) return
                if (headersRoot == null) headersRoot = getHeadersSupportFragment()?.view
                val now = SystemClock.uptimeMillis()

                if (!searchOrbReady) {
                    searchOrbReady = searchOrb.visibility == View.VISIBLE && searchOrb.isShown &&
                        searchOrb.alpha >= SETTINGS_BUTTON_ORB_READY_ALPHA && searchOrb.width > 0
                }
                val wanted = searchOrbReady && isSidebarCoveringSettingsButton(headersRoot, searchOrb, gap)

                // 出す条件は少しの間続いてから効かせる（遷移中の一瞬の揺れで出さない）。
                // 画面が止まっていると描画が来ないので、時間が来たら自分を呼び直して確かめる。
                // （描画待ちにすると、サイドバーを開き直して静止したときに取りこぼす）
                if (wanted) {
                    if (wantedSince == 0L) {
                        wantedSince = now
                        mHandler.postDelayed(this, SETTINGS_BUTTON_SHOW_DELAY_MS)
                    }
                } else {
                    wantedSince = 0L
                }
                val show = wanted && now - wantedSince >= SETTINGS_BUTTON_SHOW_DELAY_MS

                if (show == settingsShown) {
                    // 隠れているはずなのに見えていたら、その場で隠す（アニメーションの取りこぼし対策）
                    if (!show && !hiding) hideSettingsButton(gearOrb, gap)
                    return
                }
                settingsShown = show
                gearOrb.animate().cancel()
                if (show) {
                    // 検索ボタンの裏から横へ出てくる
                    hiding = false
                    gearOrb.translationX = -(gearOrb.width + gap).toFloat()
                    gearOrb.alpha = 0f
                    gearOrb.isFocusable = true
                    gearOrb.isFocusableInTouchMode = true
                    gearOrb.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(SETTINGS_BUTTON_SLIDE_MS)
                        .start()
                } else {
                    // 検索ボタンの裏へスッと隠れる（位置と透明度の両方を動かす）
                    hiding = true
                    gearOrb.isFocusable = false
                    gearOrb.isFocusableInTouchMode = false
                    if (gearOrb.hasFocus()) searchOrb.requestFocus()
                    gearOrb.animate()
                        .translationX(-(gearOrb.width + gap).toFloat())
                        .alpha(0f)
                        .setDuration(SETTINGS_BUTTON_SLIDE_MS)
                        .withEndAction { hiding = false }
                        .start()
                }
            }
        }
        // 最初のフレームで出てしまわないよう、先に隠しておく
        gearOrb.alpha = 0f
        gearOrb.isFocusable = false
        gearOrb.isFocusableInTouchMode = false
        titleBar.viewTreeObserver.addOnPreDrawListener {
            applySettingsButtonState.run()
            true
        }
        applySettingsButtonState.run()

        mSearchOrb = searchOrb
        installSearchOrbUpFromSidebar()
    }

    /**
     * サイドバーの帯が、設定ボタンの置き場所まで来ているか。
     *
     * サイドバーを畳んだ状態では帯が細い（実測48px）ので、そこへ設定ボタンだけが残ると
     * 「サイドバーからはみ出して」見える。幅ではなく位置で見て、帯が来てから横へ出す。
     */
    private fun isSidebarCoveringSettingsButton(headersRoot: View?, searchOrb: View, gap: Int): Boolean {
        if (headersRoot == null || headersRoot.width == 0 || !headersRoot.isShown) return false

        val headersLoc = IntArray(2)
        headersRoot.getLocationOnScreen(headersLoc)
        val sidebarRight = headersLoc[0] + headersRoot.width

        val orbLoc = IntArray(2)
        searchOrb.getLocationOnScreen(orbLoc)
        // 設定ボタンの最終的な左端（検索ボタンの右端 + 間隔）
        val settingsLeft = orbLoc[0] + searchOrb.width + gap

        return sidebarRight >= settingsLeft
    }

    /** 設定ボタンを検索ボタンの裏へ回して隠す（アニメーション無し。隠れている間ずっと保つためのもの）。 */
    private fun hideSettingsButton(gearOrb: View, gap: Int) {
        gearOrb.isFocusable = false
        gearOrb.isFocusableInTouchMode = false
        if (gearOrb.width > 0) gearOrb.translationX = -(gearOrb.width + gap).toFloat()
        gearOrb.alpha = 0f
    }

    /** グループ直下の ImageView を1つ返す（[SearchOrbView] のアイコンを取り出す用）。 */
    private fun firstImageView(group: ViewGroup): ImageView? {
        for (i in 0 until group.childCount) {
            (group.getChildAt(i) as? ImageView)?.let { return it }
        }
        return null
    }

    /**
     * サイドバーの一番上の行で↑を押したとき、検索ボタンへ移るようにする。
     *
     * Leanback はフォーカス移動を座標で決める。設定ボタン（歯車）は検索ボタンの右隣にあり、
     * サイドバーの行と x 座標が重なるため、何もしないと「より近い」設定ボタンへ飛んでしまい、
     * 検索ボタンには入れなくなる（実機で発生）。
     * 一番上かどうかは「上にフォーカスできる行があるか」で見るので、区切り行が先頭にあっても壊れない。
     */
    private fun installSearchOrbUpFromSidebar() {
        val grid = findHeadersGridView()
        if (grid == null) {
            Log.i(TAG, "サイドバーの↑フック: グリッドが見つからないため見送り")
            return
        }
        // グリッド自身が扱わなかったキーだけを受け取る。通常の D-pad 移動には割り込まない。
        grid.setOnUnhandledKeyListener { event ->
            if (event.keyCode != KeyEvent.KEYCODE_DPAD_UP || event.action != KeyEvent.ACTION_DOWN) {
                return@setOnUnhandledKeyListener false
            }
            // 上にまだフォーカスできる行があるなら、既定の移動に任せる
            if (hasFocusableHeaderAbove(grid)) return@setOnUnhandledKeyListener false
            val orb = mSearchOrb ?: return@setOnUnhandledKeyListener false
            // タイトル行が隠れているときは検索ボタンへ移れない。何もせず既定の移動に任せる
            if (!orb.isShown || !orb.isFocusable) return@setOnUnhandledKeyListener false
            if (!orb.requestFocus()) return@setOnUnhandledKeyListener false
            Log.i(TAG, "サイドバー最上位の↑ → 検索ボタンへ")
            true
        }
        Log.i(TAG, "サイドバーの↑フックを設定")
    }

    /** フォーカス中の行より上に、フォーカスできる行があるか。分からないときは true（既定の移動に任せる）。 */
    private fun hasFocusableHeaderAbove(grid: ViewGroup): Boolean {
        val focused = grid.focusedChild ?: return true
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (child === focused) return false
            if (child.visibility == View.VISIBLE && child.isFocusable) return true
        }
        return true
    }

    /** サイドバーの RecyclerView（[VerticalGridView]）。ID は leanback 側の持ち物なので型で探す。 */
    private fun findHeadersGridView(): VerticalGridView? {
        val root = getHeadersSupportFragment()?.view ?: return null
        return findVerticalGridViewIn(root)
    }

    private fun findVerticalGridViewIn(view: View): VerticalGridView? {
        if (view is VerticalGridView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findVerticalGridViewIn(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /**
     * 設定画面を開く。歯車ボタンと、サイドバー最下段の「設定」から入るのと同じ画面。
     *
     * 戻ってきたときの扱いは、既にある「接続設定」カードと同じにする。接続先が変わっていれば
     * API を取り直す。
     *
     * 表示の設定はここでは見ない。SettingsActivity のテーマは windowIsTranslucent なので設定画面を
     * 開いても MainActivity は PAUSED 止まりで onStop が呼ばれず、mDisplayPrefChangeListener が
     * 登録されたままになる。並び順の変更はその場で applyRuleOrder() まで済んでいる。
     */
    private fun openSettingsScreen() {
        val ctx = context ?: return
        mConnectionKeyBeforeSettings = connectionKey()
        mNeedsCheckConnectionOnResume = true
        startActivity(Intent(ctx, SettingsActivity::class.java))
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
                        SettingsCardPresenter.Item.Action.UPDATE -> {
                            AppUpdateDialogFragment.newInstance()
                                .show(childFragmentManager, AppUpdateDialogFragment.TAG)
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
                            if (!isUiAlive) return
                            response.body()?.let { getRecordedResponse ->
                                // 要求元の「続きを読み込む」アイテムが既に行から消えていることがある
                                // （同じカードを続けて選んだ、行が作り直された等）。replace(-1, …) で落ちるので何もしない。
                                val replacePosition = adapter.indexOf(item)
                                if (replacePosition < 0) {
                                    Log.i(TAG, "続き読み込み: 要求元のアイテムが既に無いため破棄 offset=${item.offset}")
                                    return@let
                                }

                                //APIのレスポンスをひとつづつアイテムとして加える。最初のアイテムだけ、Loadingアイテムを置き換える
                                //先にremoveしてaddすると高速でスクロールさせたときに描画とremoveがぶつかって落ちるのであえてreplaceに。
                                getRecordedResponse.recorded.forEachIndexed {  index, recordedProgram ->
                                    if(index == 0) {
                                        adapter.replace(replacePosition,recordedProgram)
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
                            if (!isUiAlive) return
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
                    // 利用者が待っている要求なので、ルール一覧の一斉取得とは待ち行列を分けた方を使う。
                    // 同じクライアントだと数百件の後ろに並んで、いつまでも返ってこない。
                    Log.i(TAG, "続き読み込み: 要求 offset=${item.offset} limit=${item.limit}")
                    (EpgStationV2.priorityApi ?: EpgStationV2.api)?.getRecorded(
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
                            if (!isUiAlive) return
                            Log.i(TAG, "続き読み込み: 応答 ${response.body()?.records?.size ?: 0}件 offset=${item.offset}")
                            response.body()?.let { responseRoot ->
                                // 要求元の「続きを読み込む」アイテムが既に行から消えていることがある
                                // （同じカードを続けて選んだ、行が作り直された等）。replace(-1, …) で落ちるので何もしない。
                                val replacePosition = adapter.indexOf(item)
                                if (replacePosition < 0) {
                                    Log.i(TAG, "続き読み込み: 要求元のアイテムが既に無いため破棄 offset=${item.offset}")
                                    return@let
                                }

                                //APIのレスポンスをひとつづつアイテムとして加える。最初のアイテムだけ、Loadingアイテムを置き換える
                                //先にremoveしてaddすると高速でスクロールさせたときに描画とremoveがぶつかって落ちるのであえてreplaceに。
                                responseRoot.records.forEachIndexed {  index, recordedProgram ->
                                    if(index == 0) {
                                        adapter.replace(replacePosition,recordedProgram)
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
                            if (!isUiAlive) return
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

        /**
         * @param orderedIds 行を並べる順（ruleId の並び）。null なら従来どおりカテゴリ末尾へ追加する。
         * @param ruleOrder 録画ルール行のときだけ渡す並び順の収集器。全ルールの getRecorded が
         *                  返ってきた時点で、この収集器が [applyRuleOrder] を1回だけ呼ぶ。
         *                  null のカテゴリ（最近の録画・検索履歴）の挙動は変わらない。
         */
        fun updateContentsListRowWithCategory(v1Pram:GetRecordedParam,v2Param:GetRecordedParamV2,title:String,category:Category,idInCategory:Long,orderedIds:List<Long>?=null,ruleOrder:RuleOrderCollector?=null){

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
                    // 行を作った順（サーバー順）に末尾へ足すのではなく、orderedIds の位置へ差し込む。
                    // ここを末尾追加にしていたため、「0件ルールの表示」がONだと並び順の指定が無視されていた。
                    val row = ListRow(HeaderItem(headerId, title), listRowAdapter)
                    if (orderedIds != null) {
                        addToCategoryOrdered(category, row, idInCategory, orderedIds)
                    } else {
                        addToCategory(category, row)
                    }
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
                    if (!isUiAlive) return
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
                    // いま取れた録画のうち最新の startAt を並べ替え用に報告する。このための追加リクエストはしない。
                    // body が null（APIエラー応答）でも必ず1回報告し、収集器が待ち続けないようにする。
                    ruleOrder?.report(idInCategory, response.body()?.let { it.recorded.maxOfOrNull { r -> r.startAt } })
                }
                override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                    if (!isUiAlive) return
                    Log.d(TAG,"loadRows() getRecorded API Failure")
                    ruleOrder?.report(idInCategory, null)
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
                    if (!isUiAlive) return
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
                    // いま取れた録画のうち最新の startAt を並べ替え用に報告する。このための追加リクエストはしない。
                    // body が null（APIエラー応答）でも必ず1回報告し、収集器が待ち続けないようにする。
                    ruleOrder?.report(idInCategory, response.body()?.let { it.records.maxOfOrNull { r -> r.startAt } })
                }
                override fun onFailure(call: Call<Records>, t: Throwable) {
                    if (!isUiAlive) return
                    Log.d(TAG,"loadRows() getRecorded API Failure")
                    ruleOrder?.report(idInCategory, null)
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

        /**
         * orderedIds の位置引き。
         *
         * 行を1つ足すたびに orderedIds.indexOf で線形探索すると、1回の挿入が O(件数²) になり、
         * 1125行では挿入を積み上げるだけで main スレッドが何分も止まる（ANR になる）。
         * 同じリストを使い回している間は、一度作った位置表を再利用する。
         */
        private var mOrderIndexSource: List<Long>? = null
        private var mOrderIndex: HashMap<Long, Int> = HashMap()

        private fun orderIndexOf(orderedIds: List<Long>, id: Long): Int {
            if (mOrderIndexSource !== orderedIds) {
                val map = HashMap<Long, Int>(orderedIds.size * 2)
                orderedIds.forEachIndexed { index, value -> map[value] = index }
                mOrderIndexSource = orderedIds
                mOrderIndex = map
            }
            return mOrderIndex[id] ?: -1
        }

        fun addToCategoryOrdered(cat: Category, item: Any, idInCategory: Long, orderedIds: List<Long>) {
            synchronized(this) {
                if (numOfRowInCategory[cat.ordinal] == 0) {
                    addToCategory(cat, item)
                    return
                }
                val myOrderIndex = orderIndexOf(orderedIds, idInCategory)
                val catStart = numOfRowInCategory.copyOfRange(0, cat.ordinal).sum()
                val headerRows = 2 // DividerRow + SectionRow
                val ruleStart = catStart + headerRows
                val ruleEnd = catStart + numOfRowInCategory[cat.ordinal]
                var insertPos = ruleEnd
                for (i in ruleStart until ruleEnd) {
                    val row = get(i) as? ListRow ?: continue
                    val existingId = row.headerItem.id - cat.ordinal.toLong() * 10000
                    val existingOrderIndex = orderIndexOf(orderedIds, existingId)
                    if (myOrderIndex != -1 && (existingOrderIndex == -1 || existingOrderIndex > myOrderIndex)) {
                        insertPos = i
                        break
                    }
                }
                super.add(insertPos, item)
                numOfRowInCategory[cat.ordinal]++
            }
        }

        /**
         * カテゴリ内の行を orderedIds の順に並べ直す。
         *
         * - 行の集合は変えず、順番だけを変える（同じ行が増えたり消えたりしない）。
         * - 既に目標の並びになっていれば何もせず false を返す。並べ替える必要がないときに
         *   余計な再配置・再描画を起こさないための判定。
         * - orderedIds に無い行は順位を付けず末尾へ回し、その中では元の相対順を保つ。
         *
         * @return 実際に並びを変えた場合だけ true
         */
        fun reorderCategory(cat: Category, orderedIds: List<Long>): Boolean {
            synchronized(this) {
                val catOrdinal = cat.ordinal
                val headerRows = 2 // DividerRow + SectionRow
                val totalInCat = numOfRowInCategory[catOrdinal]
                // 見出し2行しかない、または中身が1行以下なら並べ替える余地がない
                if (totalInCat <= headerRows + 1) return false

                val catStart = numOfRowInCategory.copyOfRange(0, catOrdinal).sum()
                val first = catStart + headerRows
                val last = catStart + totalInCat // この位置は含まない

                val rows = ArrayList<ListRow>(last - first)
                for (i in first until last) {
                    val row = get(i) as? ListRow ?: return false
                    rows.add(row)
                }

                val weight = HashMap<Long, Int>(orderedIds.size * 2)
                orderedIds.forEachIndexed { index, id -> weight[id] = index }

                // sortedBy は安定ソートなので、順位を持たない行は元の相対順のまま末尾へ回る
                val sorted = rows.sortedBy {
                    weight[it.headerItem.id - catOrdinal.toLong() * 10000] ?: Int.MAX_VALUE
                }
                if (sorted == rows) return false

                super.removeItems(first, rows.size)
                sorted.forEachIndexed { index, row -> super.add(first + index, row) }
                return true
            }
        }

        /** headerId を持つ行の位置。無ければ -1。並べ替えの後に選択位置を合わせ直すために使う。 */
        fun indexOfListRowByHeaderId(headerId: Long): Int {
            for (i in 0 until size()) {
                val row = get(i)
                if (row is ListRow && row.headerItem.id == headerId) return i
            }
            return -1
        }


    }

    // ヘッダーID → アイコンリソースのマップ（負値はSectionRow/Settings用の固定ID）
    private val sidebarIconMap: Map<Long, Int> by lazy {
        mapOf(
            Category.LIVE_CHANNELS.ordinal.toLong() * 10000 to R.drawable.ic_sidebar_live,
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

        /** 選んでいた行を保存しておくキー。画面が作り直されたときに使う。 */
        private const val STATE_SELECTED_ROW_ID = "main_selected_row_id"

        /** 控えていた行が現れるのを待つ間隔（ms）。ルール行は読み込みが遅いので気長に待つ。 */
        private const val SELECTION_RESTORE_RETRY_MS = 250L

        /** 同・上限回数。250ms × 120 = 30秒。ルール一覧の読み込みが終わるころまで待つ。 */
        private const val SELECTION_RESTORE_MAX_TRIES = 120

        /** タイトル行に足した設定ボタンの目印。画面を作り直したときに二重に足さないために使う。 */
        private const val SETTINGS_BUTTON_TAG = "settings_orb"

        /** 検索ボタンと設定ボタンの間隔（dp）。 */
        private const val SETTINGS_BUTTON_GAP_DP = 24

        /** 設定ボタンが横へ出るまでの時間（ms）。 */
        private const val SETTINGS_BUTTON_SLIDE_MS = 260L

        /**
         * 検索ボタンがこの透明度まで表示されてから設定ボタンを出す。
         * 起動直後はタイトル行のフェードイン中に歯車だけが先に描かれて浮いて見えたため。
         */
        private const val SETTINGS_BUTTON_ORB_READY_ALPHA = 0.9f

        /**
         * 出す条件がこの時間続いてから設定ボタンを出す。サイドバーを畳んだ直後は状態が一瞬揺れるので、
         * そのまま追うと「隠れる → うっすら出る → また隠れる」に見えてしまう。隠す方は即おこなう。
         */
        private const val SETTINGS_BUTTON_SHOW_DELAY_MS = 250L

        private const val BACKGROUND_UPDATE_DELAY = 300

        /** 番組終了時刻が取得できない場合（放送休止中など）のフォールバック更新間隔 */
        private const val PROGRAM_REFRESH_FALLBACK_INTERVAL_MS = 60 * 1000L

        /** 番組終了時刻ちょうどだとEPGStation側の番組切り替えに間に合わないことがあるための余裕時間 */
        private const val PROGRAM_END_REFRESH_BUFFER_MS = 5 * 1000L

        /** 自動更新タイマーの最小間隔（連続発火を防ぐ下限） */
        private const val MIN_PROGRAM_REFRESH_DELAY_MS = 1000L

        /** ルール一覧の読み込みが長引くときに、進み具合をログへ出す間隔（件数） */
        private const val RULE_LOAD_LOG_INTERVAL = 100

        /**
         * ルール行を一度に足す件数。
         *
         * 1126行を一気に、あるいは20件ずつでも足すと、そのひとかたまりの間フレームが落ちる
         * （実機で Skipped frames が 57〜197 件出ていた）。1チャンクを小さくして、
         * 合間にフレームを返す。
         */
        private const val RULE_ROW_CHUNK_SIZE = 5

        /** ルール行を足すチャンクの間隔。1フレームぶん空けて描画に返す */
        private const val RULE_ROW_CHUNK_INTERVAL_MS = 16L

        /**
         * ルール行の初回取得件数。
         *
         * 1126ルールで24件ずつ取ると 27000件ぶんの応答になり、起動直後の負荷と通信量が大きい。
         * まず12件だけ取って、続きは利用者が「続きを読み込む」を押したときに取る
         * （その要求はルール一覧の取得より優先して通る）。
         */
        private const val RULE_ROW_INITIAL_LIMIT = 12L

        /** 「録画の新しい順」の下ごしらえで、1ページに頼む件数 */
        private const val AGGREGATE_PAGE_LIMIT = 1000

        /** 同じく下ごしらえで読む最大ページ数。サーバーへの負荷を抑えるための上限 */
        private const val AGGREGATE_MAX_PAGES = 3
    }


}
