package com.daigorian.epcltvapp
import android.annotation.SuppressLint
import com.daigorian.epcltvapp.epgstationcaller.*
import com.daigorian.epcltvapp.epgstationv2caller.*

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
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

    private val mCardPresenter = CardPresenter()
    private val mMainMenuListRowPresenter = ListRowPresenter()
    private val mMainMenuAdapter = MainMenuAdapter(mMainMenuListRowPresenter)

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        adapter = mMainMenuAdapter

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
        Log.i(TAG, "onResume")
        super.onResume()
        if(mNeedsReloadAllOnResume && SettingsFragment.isPreferenceAllExists(requireContext())) {
            //設定画面から戻ってきたので設定を再読み込みする
            initEPGStationApi()
            mNeedsReloadAllOnResume = false
        }
        if(mNeedsReloadHistoryOnResume){
            //履歴行の読み直し
            mMainMenuAdapter.deleteCategory(Category.SEARCH_HISTORY)
            SearchFragment.getHistory(requireContext()).asReversed().forEach{
                val historyRow = contentsListRowBuilder(
                    GetRecordedParam(keyword = it),
                    GetRecordedParamV2(keyword = it),
                    it
                )
                mMainMenuAdapter.addToCategory(Category.SEARCH_HISTORY,historyRow)
            }
            mNeedsReloadHistoryOnResume = false

        }

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
                            loadRows()
                        } else {
                            //Version 1で初期化
                            Log.d(TAG, "initEPGStationApi() detect Version 1.x.x")
                            EpgStationV2.api = null
                            EpgStation.initAPI(baseUrl)
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
        mDefaultBackground = ContextCompat.getDrawable(requireContext(), R.drawable.default_background)
        mMetrics = DisplayMetrics()
        //from API LEVEL 30
        //requireActivity().display?.getRealMetrics(mMetrics)
        //for lower API LEVEL
        requireActivity().windowManager.defaultDisplay.getMetrics(mMetrics)

    }

    private fun setupUIElements() {
        title = getString(R.string.browse_title)
        // over title
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        // set fastLane (or headers) background color
        brandColor = ContextCompat.getColor(requireContext(), R.color.background_epgstation)
        // set search icon color
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.search_opaque)
    }

    private fun loadRows() {

        //内容クリア
        mMainMenuAdapter.clear()


        EpgStationV2.api?.let{ api ->
            // EPGStation V2.x.x　の場合だけ録画中列を作る
            val listRowAdapter = ArrayObjectAdapter(mCardPresenter)
            val header = HeaderItem( getString(R.string.now_on_recording))
            mMainMenuAdapter.addToCategory(Category.ON_RECORDING,ListRow(header, listRowAdapter))

            api.getRecording().enqueue(object : Callback<Records> {
                override fun onResponse(call: Call<Records>, response: Response<Records>) {
                    response.body()?.let { getRecordingResponse ->
                        if (getRecordingResponse.records.isNotEmpty()) {
                            //"録画中"の列を追加。
                            getRecordingResponse.records.forEach {
                                listRowAdapter.add(it)
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
        val recentlyRecorded = contentsListRowBuilder(
            GetRecordedParam(),
            GetRecordedParamV2(),
            getString(R.string.recent_videos)
        )
        mMainMenuAdapter.addToCategory(Category.RECENTLY_RECORDED,recentlyRecorded)


        //履歴行の追加
        SearchFragment.getHistory(requireContext()).asReversed().forEach{
            val historyRow = contentsListRowBuilder(
                GetRecordedParam(keyword = it),
                GetRecordedParamV2(keyword = it),
                it
            )
            mMainMenuAdapter.addToCategory(Category.SEARCH_HISTORY,historyRow)
        }

        //ルールの並び順を表すフラグ。デフォルトfalse。
        val isNewestFirst = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.pref_key_rules_order_is_newest_first),false)

        //次の横の列。録画ルール。録画ルールの数だけ行が増える。
        EpgStation.api?.getRulesList()?.enqueue(object : Callback<List<RuleList>> {
            override fun onResponse(call: Call<List<RuleList>>, response: Response<List<RuleList>>) {
                response.body()?.let{ it ->
                    val rules = if(isNewestFirst){it.reversed()}else{it}
                    rules.forEach { rule ->

                        //録画ルールにキーワードが設定されていない場合、キーワードの代わりにルールIDをセット
                        val keyword:String = if ( rule.keyword.isNullOrEmpty() ){
                            getString(R.string.rule_id_is_x, rule.id.toString())
                        }else{
                            rule.keyword
                        }
                        val recordedByRule = contentsListRowBuilder(
                            GetRecordedParam(rule= rule.id),
                            GetRecordedParamV2(ruleId= rule.id),
                            keyword
                        )
                        mMainMenuAdapter.addToCategory(Category.RECORDED_BY_RULES,recordedByRule)
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
                    rules.forEach { rule ->

                        //録画ルールにキーワードが設定されていない場合、キーワードの代わりにルールIDをセット
                        val keyword:String = if ( rule.searchOption?.keyword.isNullOrEmpty() ){
                            getString(R.string.rule_id_is_x, rule.id.toString())
                        }else{
                            rule.searchOption?.keyword!!
                        }
                        val recordedByRule = contentsListRowBuilder(
                            GetRecordedParam(rule= rule.id),
                            GetRecordedParamV2(ruleId= rule.id),
                            keyword
                        )
                        mMainMenuAdapter.addToCategory(Category.RECORDED_BY_RULES,recordedByRule)
                    }
                }
            }
            override fun onFailure(call: Call<Rules>, t: Throwable) {
                Log.d(TAG,"loadRows() getRulesList API Failure")
                Toast.makeText(context!!, R.string.connect_epgstation_failed, Toast.LENGTH_LONG).show()
            }
        })

        //"設定"　のボタンが乗る行
        val gridHeader = HeaderItem(getString(R.string.settings))
        val gridPresenter = GridItemPresenter()
        val gridRowAdapter = ArrayObjectAdapter(gridPresenter)
        gridRowAdapter.add(resources.getString(R.string.settings))
        gridRowAdapter.add(resources.getString(R.string.reload))
        if (isNewestFirst){
            gridRowAdapter.add(resources.getString(R.string.set_oldest_rule_first))
        }else{
            gridRowAdapter.add(resources.getString(R.string.set_newest_rule_first))
        }


        mMainMenuAdapter.addToCategory(Category.SETTINGS,ListRow(gridHeader, gridRowAdapter))



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
                is String -> {
                    //設定、再読み込みなどのアイテム
                    when {
                        item.contains(getString(R.string.settings)) -> {
                            val intent = Intent(context!!, SettingsActivity::class.java)
                            startActivity(intent)
                            mNeedsReloadAllOnResume = true
                        }
                        item.contains(getString(R.string.reload)) -> {
                            loadRows()
                        }
                        item.contains(getString(R.string.set_newest_rule_first)) -> {
                            PreferenceManager.getDefaultSharedPreferences(context)
                                .edit()
                                .putBoolean(getString(R.string.pref_key_rules_order_is_newest_first),true)
                                .commit()
                            loadRows()
                        }
                        item.contains(getString(R.string.set_oldest_rule_first)) -> {
                            PreferenceManager.getDefaultSharedPreferences(context)
                                .edit()
                                .putBoolean(getString(R.string.pref_key_rules_order_is_newest_first),false)
                                .commit()
                            loadRows()
                        }
                        else -> {
                            Toast.makeText(context!!, item, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun contentsListRowBuilder(v1Pram:GetRecordedParam,v2Param:GetRecordedParamV2,title:String):ListRow{
        // 空っぽの、タイトルだけ設定されたListRowを作る
        val listRowAdapter = ArrayObjectAdapter(mCardPresenter)
        val header = HeaderItem(title)
        val result = ListRow(header, listRowAdapter)

        // APIのコールバックでListRowの中身をセットするように仕掛ける
        // EPGStation V1.x.x
        EpgStation.api?.getRecorded(
            limit = v1Pram.limit,
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
                    //APIのレスポンスをひとつづつアイテムとして加える。
                    getRecordedResponse.recorded.forEach {  recordedProgram ->
                        listRowAdapter.add(recordedProgram)
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
            limit = v2Param.limit,
            isReverse = v2Param.isReverse,
            ruleId = v2Param.ruleId,
            channelId = v2Param.channelId,
            genre = v2Param.genre,
            keyword = v2Param.keyword,
            hasOriginalFile = v2Param.hasOriginalFile )?.enqueue(object : Callback<Records> {
            override fun onResponse(call: Call<Records>, response: Response<Records>) {
                response.body()?.let { getRecordedResponse ->
                    //APIのレスポンスをひとつづつアイテムとして加える。
                    getRecordedResponse.records.forEach {
                        listRowAdapter.add(it)
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
                }
            }
            override fun onFailure(call: Call<Records>, t: Throwable) {
                Log.d(TAG,"loadRows() getRecorded API Failure")
                Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
            }
        })
        return result
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
        mBackgroundTimer?.cancel()
        mBackgroundTimer = Timer()
        mBackgroundTimer?.schedule(UpdateBackgroundTask(), BACKGROUND_UPDATE_DELAY.toLong())
    }

    private inner class UpdateBackgroundTask : TimerTask() {

        override fun run() {
            mHandler.post { updateBackground(mBackgroundUri) }
        }
    }

    private inner class GridItemPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val view = TextView(parent.context)
            view.layoutParams = ViewGroup.LayoutParams(GRID_ITEM_WIDTH, GRID_ITEM_HEIGHT)
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setBackgroundColor(ContextCompat.getColor(context!!, R.color.default_background))
            view.setTextColor(Color.WHITE)
            view.gravity = Gravity.CENTER
            return ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            (viewHolder.view as TextView).text = item as String
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
    }

    enum class Category {
        //メニューはこの順番で並びます。
        ON_RECORDING,
        RECENTLY_RECORDED,
        SEARCH_HISTORY,
        RECORDED_BY_RULES,
        SETTINGS
    }

    private inner class MainMenuAdapter(presenter: Presenter?) : ArrayObjectAdapter(presenter) {

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
                        Category.SEARCH_HISTORY ->{
                            //検索履歴というセクション行を、さらに上に加える
                            super.add(index,SectionRow(getString(R.string.search_history)))
                            numOfRowInCategory[cat.ordinal]++
                            //さらにその上に区切り線を乗せる。
                            super.add(index,DividerRow())
                            numOfRowInCategory[cat.ordinal]++
                        }
                        Category.RECORDED_BY_RULES ->{
                            //検索結果というセクション行を、さらに上に加える
                            super.add(index,SectionRow(getString(R.string.by_rec_rules)))
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

        fun sortRulesByRecordedDate(){
            synchronized(this){
                //bubble sort で新しいのを下からあげていく
                val startIndex = numOfRowInCategory.copyOfRange (0,Category.RECORDED_BY_RULES.ordinal).sum() +3
                val endIndex = numOfRowInCategory.copyOfRange(0,Category.RECORDED_BY_RULES.ordinal+1).sum() -1
                for(i in startIndex until endIndex){
                    for(j in endIndex downTo i){
                        //下のアイテムJ
                        val itemJ = (get(j) as? ListRow)?.adapter
                        //上のアイテムJ-1
                        val itemJMinus1 = (get(j-1) as? ListRow)?.adapter

                        if (itemJ != null && itemJ.size() >0){
                            //下のアイテムに録画済の要素がある

                            if (itemJMinus1 != null && itemJMinus1.size() >0) {
                                //上のアイテムに録画済の要素がある
                                val startTimeOfJ = (itemJ.get(0) as? RecordedProgram)?.startAt
                                val startTimeOfJMinus1 = (itemJMinus1.get(0) as? RecordedProgram)?.startAt
                                //下のアイテムが上のアイテムより録画時間が新しい
                                if (startTimeOfJ != null && startTimeOfJMinus1 != null && startTimeOfJ > startTimeOfJMinus1) {
                                    move(j, j - 1)
                                }
                            }else{
                                //上のアイテムに録画済の要素がない
                                move(j, j - 1)
                            }
                        }
                    }
                }
            }
        }


    }


    companion object {
        private const val TAG = "MainFragment"

        private const val BACKGROUND_UPDATE_DELAY = 300
        private const val GRID_ITEM_WIDTH = 200
        private const val GRID_ITEM_HEIGHT = 200
    }


}