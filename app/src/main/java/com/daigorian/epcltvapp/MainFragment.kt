package com.daigorian.epcltvapp

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

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        Settings.loadDefaultPreferencesIfNotExist(requireContext())
        EpgStation.reloadAPI(requireContext())

        prepareBackgroundManager()

        setupUIElements()

        loadRows()

        setupEventListeners()
    }

    override fun onResume() {
        super.onResume()
        if(Settings.hasChanged()){
            loadRows()
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: " + mBackgroundTimer?.toString())
        mBackgroundTimer?.cancel()
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
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        var numOfRow = 0L

        //最初の横の列 設定
        val gridHeader = HeaderItem(numOfRow++, getString(R.string.settings))

        val mGridPresenter = GridItemPresenter()
        val gridRowAdapter = ArrayObjectAdapter(mGridPresenter)
        gridRowAdapter.add(resources.getString(R.string.settings))
        rowsAdapter.add(ListRow(gridHeader, gridRowAdapter))



        //動画のカードの表示の処理を行うCardPresenterは横の列ごとに設定する。
        val cardPresenter = CardPresenter()

        //横の列を作る
        //APIで最近の録画を取得する。問題なく取得出来たら、一列追加してそれをカードに加えていく。
        EpgStation.api.getRecorded().enqueue(object : Callback<GetRecordedResponse> {
            override fun onResponse(call: Call<GetRecordedResponse>, response: Response<GetRecordedResponse>) {

                //の列を追加。"最近の録画"
                val listRowAdapter = ArrayObjectAdapter(cardPresenter)
                val header = HeaderItem(numOfRow++ , getString(R.string.recent_videos))
                response.body()!!.recorded.forEach {
                    listRowAdapter.add(it)
                }
                // 完成した横の列を、縦の列に加える。
                // 非同期なのでAPIが終わるタイミングによって変な場所に挿入されることがあるので、
                // Indexを指定して常にSettingsの次に加える
                rowsAdapter.add(1,ListRow(header, listRowAdapter))
            }
            override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                Log.d(TAG,"loadRows() getRecorded API Failure")
                Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
            }
        })



        //次の横の列。録画ルール。録画ルールの数だけ行が増える。
        EpgStation.api.getRulesList().enqueue(object : Callback<Array<RuleList>> {
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
                    EpgStation.api.getRecorded(rule=rule.id).enqueue(object : Callback<GetRecordedResponse> {
                        override fun onResponse(call: Call<GetRecordedResponse>, response: Response<GetRecordedResponse>) {
                            response.body()!!.recorded.forEach { recordedProgram ->
                                ruleListRowAdapter.add(recordedProgram)
                            }
                        }
                        override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                            Log.d(TAG,"loadRows() getRecorded API Failure")
                            Toast.makeText(context!!, R.string.connect_epgstation_failed, Toast.LENGTH_LONG).show()
                        }
                    })
                    //最初の行が最近の録画になり、最終行が設定になるように常に二番目に加える。
                    rowsAdapter.add(ListRow(ruleHeader, ruleListRowAdapter))
                }
            }
            override fun onFailure(call: Call<Array<RuleList>>, t: Throwable) {
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
            } else if (item is String) {
                if (item.contains(getString(R.string.settings))) {
                    val intent = Intent(context!!, SettingsActivity::class.java)
                    startActivity(intent)
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
                mBackgroundUri = EpgStation.getThumbnailURL(item.id.toString())
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

    companion object {
        private const val TAG = "MainFragment"

        private const val BACKGROUND_UPDATE_DELAY = 300
        private const val GRID_ITEM_WIDTH = 200
        private const val GRID_ITEM_HEIGHT = 200
    }
}