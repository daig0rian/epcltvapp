package com.daigorian.epcltvapp
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
    private var mNeedsReloadOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        if(!SettingsFragment.isPreferenceAllExists(requireContext())){
            //設定されていないPreference項目があった場合は設定画面を開く
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
            mNeedsReloadOnResume = true

        }else{
            //設定が最初から読み込めた場合はそれに合わせてAPIを初期化
            initEPGStationApi()
        }

        prepareBackgroundManager()

        setupUIElements()

        loadRows()

        setupEventListeners()
    }

    override fun onResume() {
        super.onResume()
        if(mNeedsReloadOnResume) {
            //設定画面から戻ってきたので設定を再読み込みする
            initEPGStationApi()
            //行をロードしなおす
            loadRows()
            mNeedsReloadOnResume = false
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

        val apiVersion = PreferenceManager.getDefaultSharedPreferences(context).getString(getString(R.string.pref_key_epgstation_version),"")
        if (apiVersion == getString(R.string.pref_options_epgstation_version_val_1)) {
            //Preferenceに EPGStation Version 1　が設定されていた場合
            EpgStation.initAPI(
                PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(getString(R.string.pref_key_ip_addr), "")!!,
                PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(getString(R.string.pref_key_port_num), "")!! )
            EpgStation.default_limit = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(getString(R.string.pref_key_fetch_limit), "")!!

        }else if (apiVersion == getString(R.string.pref_options_epgstation_version_val_2)) {
            //Preferenceに EPGStation Version 2　が設定されていた場合
            EpgStationV2.initAPI(
                PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(getString(R.string.pref_key_ip_addr), "")!!,
                PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(getString(R.string.pref_key_port_num), "")!! )
            EpgStationV2.default_limit = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(getString(R.string.pref_key_fetch_limit), "")!!

        }
    }

    private fun prepareBackgroundManager() {

        mBackgroundManager = BackgroundManager.getInstance(activity)
        mBackgroundManager.attach(requireActivity().window)
        mDefaultBackground = ContextCompat.getDrawable(requireContext(), R.drawable.default_background)
        mMetrics = DisplayMetrics()
        requireActivity().display?.getRealMetrics(mMetrics)
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


        //縦の列を作る
        val rowsAdapter = MainMenuAdapter(ListRowPresenter())
        var numOfRow = 0L

        //最後の横の列 設定
        val gridHeader = HeaderItem(numOfRow++, getString(R.string.settings))

        val mGridPresenter = GridItemPresenter()
        val gridRowAdapter = ArrayObjectAdapter(mGridPresenter)
        gridRowAdapter.add(resources.getString(R.string.settings))
        rowsAdapter.addSettings(ListRow(gridHeader, gridRowAdapter))


        //動画のカードの表示の処理を行うCardPresenterは横の列ごとに設定する。
        val cardPresenter = CardPresenter()


        //横の列を作る
        //APIで最近の録画を取得する。問題なく取得出来たら、一列追加してそれをカードに加えていく。
        // EPGStation V1.x.x
        EpgStation.api?.getRecorded()?.enqueue(object : Callback<GetRecordedResponse> {
            override fun onResponse(call: Call<GetRecordedResponse>, response: Response<GetRecordedResponse>) {

                //の列を追加。"最近の録画"
                val listRowAdapter = ArrayObjectAdapter(cardPresenter)
                val header = HeaderItem(numOfRow++ , getString(R.string.recent_videos))
                response.body()?.recorded?.forEach {
                    listRowAdapter.add(it)
                }
                // 完成した横の列を、縦の列に加える。
                rowsAdapter.addRecentlyRecorded(ListRow(header, listRowAdapter))
            }
            override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                Log.d(TAG,"loadRows() getRecorded API Failure")
                Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
            }
        })

        // EPGStation V2.x.x
        EpgStationV2.api?.getRecording()?.enqueue(object : Callback<Records> {
            override fun onResponse(call: Call<Records>, response: Response<Records>) {
                if(response.body()?.records?.isNotEmpty() == true) {
                    //"録画中"の列を追加。
                    val listRowAdapter = ArrayObjectAdapter(cardPresenter)
                    val header = HeaderItem(numOfRow++, getString(R.string.now_on_recording))
                    response.body()?.records?.forEach {
                        listRowAdapter.add(it)
                    }
                    // 完成した横の列を、縦の列に加える。
                    rowsAdapter.addOnRecording(ListRow(header, listRowAdapter))
                }
            }
            override fun onFailure(call: Call<Records>, t: Throwable) {
                Log.d(TAG,"loadRows() getRecorded API Failure")
                Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
            }
        })
        EpgStationV2.api?.getRecorded()?.enqueue(object : Callback<Records> {
            override fun onResponse(call: Call<Records>, response: Response<Records>) {

                //"最近の録画"の列を追加。
                val listRowAdapter = ArrayObjectAdapter(cardPresenter)
                val header = HeaderItem(numOfRow++ , getString(R.string.recent_videos))
                response.body()?.records?.forEach {
                    listRowAdapter.add(it)
                }
                // 完成した横の列を、縦の列に加える。
                rowsAdapter.addRecentlyRecorded(ListRow(header, listRowAdapter))
            }
            override fun onFailure(call: Call<Records>, t: Throwable) {
                Log.d(TAG,"loadRows() getRecorded API Failure")
                Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
            }
        })



        //次の横の列。録画ルール。録画ルールの数だけ行が増える。
        EpgStation.api?.getRulesList()?.enqueue(object : Callback<Array<RuleList>> {
            override fun onResponse(call: Call<Array<RuleList>>, response: Response<Array<RuleList>>) {
                response.body()?.forEach { rule ->

                    //録画ルールにキーワードが設定されていない場合、キーワードの代わりにルールIDをセット
                    val keyword:String = if ( rule.keyword.isNullOrEmpty() ){
                        getString(R.string.rule_id_is_x, rule.id.toString())
                    }else{
                        rule.keyword
                    }

                    val ruleListRowAdapter = ArrayObjectAdapter(cardPresenter)
                    val ruleHeader = HeaderItem(numOfRow++, keyword)
                    EpgStation.api?.getRecorded(rule=rule.id)?.enqueue(object : Callback<GetRecordedResponse> {
                        override fun onResponse(call: Call<GetRecordedResponse>, response: Response<GetRecordedResponse>) {
                            response.body()?.recorded?.forEach { recordedProgram ->
                                ruleListRowAdapter.add(recordedProgram)
                            }
                        }
                        override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                            Log.d(TAG,"loadRows() getRecorded API Failure")
                            Toast.makeText(context!!, R.string.connect_epgstation_failed, Toast.LENGTH_LONG).show()
                        }
                    })
                    rowsAdapter.addRecordedByRules(ListRow(ruleHeader, ruleListRowAdapter))
                }
            }
            override fun onFailure(call: Call<Array<RuleList>>, t: Throwable) {
                Log.d(TAG,"loadRows() getRulesList API Failure")
                Toast.makeText(context!!, R.string.connect_epgstation_failed, Toast.LENGTH_LONG).show()
            }
        })
        EpgStationV2.api?.getRules()?.enqueue(object : Callback<Rules> {
            override fun onResponse(call: Call<Rules>, response: Response<Rules>) {
                response.body()?.rules?.forEach { rule ->

                    //録画ルールにキーワードが設定されていない場合、キーワードの代わりにルールIDをセット
                    val keyword:String = if ( rule.searchOption?.keyword.isNullOrEmpty() ){
                        getString(R.string.rule_id_is_x, rule.id.toString())
                    }else{
                        rule.searchOption?.keyword!!
                    }

                    val ruleListRowAdapter = ArrayObjectAdapter(cardPresenter)
                    val ruleHeader = HeaderItem(numOfRow++, keyword)
                    EpgStationV2.api?.getRecorded(ruleId= rule.id)?.enqueue(object : Callback<Records> {
                        override fun onResponse(call: Call<Records>, response: Response<Records>) {
                            response.body()?.records?.forEach { recordedItem ->
                                ruleListRowAdapter.add(recordedItem)
                            }
                        }
                        override fun onFailure(call: Call<Records>, t: Throwable) {
                            Log.d(TAG,"loadRows() getRecorded API Failure")
                            Toast.makeText(context!!, R.string.connect_epgstation_failed, Toast.LENGTH_LONG).show()
                        }
                    })
                    rowsAdapter.addRecordedByRules(ListRow(ruleHeader, ruleListRowAdapter))
                }
            }
            override fun onFailure(call: Call<Rules>, t: Throwable) {
                Log.d(TAG,"loadRows() getRulesList API Failure")
                Toast.makeText(context!!, R.string.connect_epgstation_failed, Toast.LENGTH_LONG).show()
            }
        })

        adapter = rowsAdapter
    }

    private fun setupEventListeners() {
        setOnSearchClickedListener {
            Intent(activity, SearchActivity::class.java).also { intent ->
                startActivity(intent)
            }
        }

        onItemViewClickedListener = ItemViewClickedListener()
        onItemViewSelectedListener = ItemViewSelectedListener()
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {

            if (item is RecordedProgram) {
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
            } else if (item is RecordedItem) {
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
            } else if (item is String) {
                if (item.contains(getString(R.string.settings))) {
                    val intent = Intent(context!!, SettingsActivity::class.java)
                    startActivity(intent)
                    mNeedsReloadOnResume = true
                } else {
                    Toast.makeText(context!!, item, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
        override fun onItemSelected(
            itemViewHolder: Presenter.ViewHolder?, item: Any?,
            rowViewHolder: RowPresenter.ViewHolder, row: Row
        ) {
            if (item is RecordedProgram) {
                //V1
                mBackgroundUri = EpgStation.getThumbnailURL(item.id.toString())
                startBackgroundTimer()
            }else if (item is RecordedItem) {
                //V2
                mBackgroundUri = if(!item.thumbnails.isNullOrEmpty())
                {
                    EpgStationV2.getThumbnailURL(item.thumbnails[0].toString())
                }
                else
                {
                    ""
                }
                startBackgroundTimer()
            }
        }
    }

    private fun updateBackground(uri: String?) {
        val width = mMetrics.widthPixels
        val height = mMetrics.heightPixels
        Glide.with(requireContext())
            .load(uri)
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

    private inner class MainMenuAdapter(presenter: Presenter?) : ArrayObjectAdapter(presenter) {

        //メニューはこの順番で並びます。非同期に項目が追加されるので挿入位置地をこのクラスで管理します
        private var numOfOnRecoding = 0
        private var numOfRecentlyRecorded = 0
        private var numOfDivider1 = 0
        private var numOfRecordedByRules = 0
        private var numOfDivider2 = 0
        private var numOfSettings = 0

        fun addOnRecording(item: Any?) {
            val index = numOfOnRecoding
            if (numOfDivider1==0){
                super.add(numOfRecentlyRecorded,DividerRow())
                numOfDivider1++
            }
            super.add(index,item)
            numOfRecentlyRecorded++
        }
        fun addRecentlyRecorded(item: Any?) {
            val index = numOfOnRecoding + numOfRecentlyRecorded
            if (numOfDivider1==0){
                super.add(numOfRecentlyRecorded,DividerRow())
                numOfDivider1++
            }
            super.add(index,item)
            numOfRecentlyRecorded++
        }

        fun addRecordedByRules(item: Any?) {
            val index = numOfOnRecoding + numOfRecentlyRecorded + numOfDivider1 + numOfRecordedByRules

            if (numOfDivider2==0){
                super.add(index,DividerRow())
                numOfDivider2++
            }
            super.add(index,item)
            numOfRecordedByRules++
        }
        fun addSettings(item: Any?) {
            val index = numOfOnRecoding + numOfRecentlyRecorded + numOfDivider1 + numOfRecordedByRules + numOfDivider2 + numOfSettings
            super.add(index,item)
            numOfSettings++
        }

        

    }


    companion object {
        private const val TAG = "MainFragment"

        private const val BACKGROUND_UPDATE_DELAY = 300
        private const val GRID_ITEM_WIDTH = 200
        private const val GRID_ITEM_HEIGHT = 200
    }


}