package com.daigorian.epcltvapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.app.DetailsSupportFragmentBackgroundController
import androidx.leanback.widget.*
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import com.daigorian.epcltvapp.epgstationcaller.GetRecordedResponse
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import com.daigorian.epcltvapp.epgstationv2caller.Records
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.math.roundToInt

/**
 * A wrapper fragment for leanback details screens.
 * It shows a detailed view of video and its metadata plus related videos.
 */
class VideoDetailsFragment : DetailsSupportFragment() {

    private var mSelectedRecordedProgram: RecordedProgram? = null
    private var mSelectedRecordedItem: RecordedItem? = null

    private lateinit var mDetailsBackground: DetailsSupportFragmentBackgroundController
    private lateinit var mPresenterSelector: ClassPresenterSelector
    private lateinit var mAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate DetailsFragment")
        super.onCreate(savedInstanceState)

        mDetailsBackground = DetailsSupportFragmentBackgroundController(this)

        mSelectedRecordedProgram = requireActivity().intent.getSerializableExtra(DetailsActivity.RECORDEDPROGRAM) as RecordedProgram?
        mSelectedRecordedItem= requireActivity().intent.getSerializableExtra(DetailsActivity.RECORDEDITEM) as RecordedItem?

        when {
            mSelectedRecordedProgram != null -> {
                //V1
                mPresenterSelector = ClassPresenterSelector()
                mAdapter = ArrayObjectAdapter(mPresenterSelector)
                setupDetailsOverviewRow()
                setupDetailsOverviewRowPresenter()
                setupRelatedMovieListRow()
                adapter = mAdapter
                initializeBackground(EpgStation.getThumbnailURL(mSelectedRecordedProgram?.id.toString()))
                onItemViewClickedListener = ItemViewClickedListener()
            }
            mSelectedRecordedItem != null -> {
                //V2
                mPresenterSelector = ClassPresenterSelector()
                mAdapter = ArrayObjectAdapter(mPresenterSelector)
                setupDetailsOverviewRow()
                setupDetailsOverviewRowPresenter()
                setupRelatedMovieListRow()
                adapter = mAdapter
                initializeBackground(EpgStationV2.getThumbnailURL(mSelectedRecordedItem?.thumbnails?.get(0).toString()))
                onItemViewClickedListener = ItemViewClickedListener()
            }
            else -> {
                val intent = Intent(requireContext(), MainActivity::class.java)
                startActivity(intent)
            }
        }
    }

    private fun initializeBackground(imageURL: String) {
        mDetailsBackground.enableParallax()
        Glide.with(requireContext())
            .asBitmap()
            .centerCrop()
            .error(R.drawable.default_background)
            .load(imageURL)
            .into<CustomTarget<Bitmap>>(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    bitmap: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    mDetailsBackground.coverBitmap = bitmap
                    mAdapter.notifyArrayItemRangeChanged(0, mAdapter.size())
                }
                override fun onLoadCleared( placeholder: Drawable?) {}
            })
    }

    private fun setupDetailsOverviewRow() {
        Log.d(TAG, "setupDetailsOverviewRow()")

        val row = if (mSelectedRecordedProgram!=null){
            //V1
            DetailsOverviewRow(mSelectedRecordedProgram)
        }else {
            //V2
            DetailsOverviewRow(mSelectedRecordedItem)
        }
        val urlString = if (mSelectedRecordedProgram!=null){
            //V1
            EpgStation.getThumbnailURL(mSelectedRecordedProgram?.id.toString())
        }else {
            //V2
            EpgStationV2.getThumbnailURL(mSelectedRecordedItem?.thumbnails?.get(0).toString())
        }

        row.imageDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.default_background)
        val width = convertDpToPixel(requireContext(), DETAIL_THUMB_WIDTH)
        val height = convertDpToPixel(requireContext(), DETAIL_THUMB_HEIGHT)
        Glide.with(requireContext())
            .load(urlString)
            .centerCrop()
            .error(R.drawable.default_background)
            .into<CustomTarget<Drawable>>(object : CustomTarget<Drawable>(width, height) {
                override fun onResourceReady(
                    drawable: Drawable,
                    transition: Transition<in Drawable>?
                ) {
                    Log.d(TAG, "details overview card image url ready: $drawable")
                    row.imageDrawable = drawable
                    mAdapter.notifyArrayItemRangeChanged(0, mAdapter.size())
                }
                override fun onLoadCleared( placeholder: Drawable?) {}
            })

        val actionAdapter = ArrayObjectAdapter()

        mSelectedRecordedProgram?.let {
            //V1
            // オリジナルのTSがある場合は "TS を再生" アクションアダプタを追加
            if (it.original) {
                actionAdapter.add(
                    Action(
                        ACTION_WATCH_ORIGINAL_TS,
                        getString(R.string.play_ts)
                    )
                )
            }
            // エンコード済みがある場合は "XX を再生" アクションアダプタを追加
            it.encoded?.forEach { encodedProgram ->
                actionAdapter.add(
                    Action(
                        encodedProgram.encodedId,
                        getString(R.string.play_x,encodedProgram.name)
                    )
                )
            }
        }
        mSelectedRecordedItem?.let {  recordedItem ->
            //V2
            recordedItem.videoFiles?.forEach { videoFIle ->
                actionAdapter.add(
                    Action(
                        videoFIle.id,
                        getString(R.string.play_x,videoFIle.name)
                    )
                )
            }
        }



        row.actionsAdapter = actionAdapter

        mAdapter.add(row)
    }

    private fun setupDetailsOverviewRowPresenter() {
        // Set detail background.
        val detailsPresenter = FullWidthDetailsOverviewRowPresenter(DetailsDescriptionPresenter())
        detailsPresenter.backgroundColor =
            ContextCompat.getColor(requireContext(), R.color.selected_background)
        // Hook up transition element.
        val sharedElementHelper = FullWidthDetailsOverviewSharedElementHelper()
        sharedElementHelper.setSharedElementEnterTransition(
            activity, DetailsActivity.SHARED_ELEMENT_NAME
        )
        detailsPresenter.setListener(sharedElementHelper)
        detailsPresenter.isParticipatingEntranceTransition = true

        detailsPresenter.onActionClickedListener = OnActionClickedListener { action ->

            val playerPkgName = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(getString(R.string.pref_key_player),"")
            if( playerPkgName == getString(R.string.pref_options_movie_player_val_INTERNAL)) {
                //内蔵プレーヤー
                val intent = Intent(requireContext(), PlaybackActivity::class.java)
                mSelectedRecordedProgram?.let{intent.putExtra(DetailsActivity.RECORDEDPROGRAM, mSelectedRecordedProgram)}
                mSelectedRecordedItem?.let{intent.putExtra(DetailsActivity.RECORDEDITEM, mSelectedRecordedItem)}
                intent.putExtra(DetailsActivity.ACTIONID, action.id)
                startActivity(intent)

            }else {
                //外部プレーヤー
                val urlStrings = if (mSelectedRecordedProgram != null) {
                    //V1
                    if (action.id == ACTION_WATCH_ORIGINAL_TS) {
                        EpgStation.getTsVideoURL(mSelectedRecordedProgram?.id.toString())
                    } else {
                        EpgStation.getEncodedVideoURL(
                            mSelectedRecordedProgram?.id.toString(),
                            action.id.toString()
                        )
                    }
                }else{
                    //V2
                    EpgStationV2.getVideoURL(action.id.toString())
                }

                val uri = Uri.parse(urlStrings)

                val extPlayerIntent = Intent(Intent.ACTION_VIEW)

                extPlayerIntent.setPackage(playerPkgName)
                extPlayerIntent.setDataAndTypeAndNormalize(uri, "video/*")
                //V1
                mSelectedRecordedProgram?.let{extPlayerIntent.putExtra("title", mSelectedRecordedProgram?.name)}
                //V2
                mSelectedRecordedItem?.let{extPlayerIntent.putExtra("title", mSelectedRecordedItem?.name)}

                try {
                    startActivity(extPlayerIntent)
                } catch (ex: ActivityNotFoundException) {
                    //外部プレーヤーがインストールされていないためGoogle Play Marketを表示
                    Toast.makeText(requireContext(), getString(R.string.please_install_external_player), Toast.LENGTH_LONG).show()

                    try{
                        val marketIntent = Intent(Intent.ACTION_VIEW)
                        marketIntent.data = Uri.parse("market://details?id=$playerPkgName")
                        startActivity(marketIntent)
                    } catch (ex: ActivityNotFoundException) {
                        //Google Play Marketすらないからどうにもできない
                    }
                }
            }

        }
        mPresenterSelector.addClassPresenter(DetailsOverviewRow::class.java, detailsPresenter)
    }

    private fun setupRelatedMovieListRow() {

        //現在表示中の動画と同じルールIDを持った動画を検索してならべる。
        mSelectedRecordedProgram?.ruleId?.let{
            //V1
            val listRowAdapter = ArrayObjectAdapter(CardPresenter())
            EpgStation.api?.getRecorded(rule = mSelectedRecordedProgram?.ruleId)?.enqueue(object : Callback<GetRecordedResponse> {
                override fun onResponse(call: Call<GetRecordedResponse>, response: Response<GetRecordedResponse>) {
                    response.body()!!.recorded.forEach {
                        listRowAdapter.add(it)
                    }
                }
                override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                    Log.d(TAG,"setupRelatedMovieListRow() getRecorded API Failure")
                    Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_SHORT).show()
                }
            })

            val header = HeaderItem(0, getString(R.string.videos_in_same_rule))
            mAdapter.add(ListRow(header, listRowAdapter))
            mPresenterSelector.addClassPresenter(ListRow::class.java, ListRowPresenter())
        }
        mSelectedRecordedItem?.ruleId?.let{
            //V2
            val listRowAdapter = ArrayObjectAdapter(CardPresenter())
            EpgStationV2.api?.getRecorded(ruleId = mSelectedRecordedItem?.ruleId)?.enqueue(object : Callback<Records> {
                override fun onResponse(call: Call<Records>, response: Response<Records>) {
                    response.body()!!.records.forEach {
                        listRowAdapter.add(it)
                    }
                }
                override fun onFailure(call: Call<Records>, t: Throwable) {
                    Log.d(TAG,"setupRelatedMovieListRow() getRecorded API Failure")
                    Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_SHORT).show()
                }
            })

            val header = HeaderItem(0, getString(R.string.videos_in_same_rule))
            mAdapter.add(ListRow(header, listRowAdapter))
            mPresenterSelector.addClassPresenter(ListRow::class.java, ListRowPresenter())
        }
    }

    private fun convertDpToPixel(context: Context, dp: Int): Int {
        val density = context.applicationContext.resources.displayMetrics.density
        return (dp.toFloat() * density).roundToInt()
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder?,
            item: Any?,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            if (item is RecordedProgram) {
                Log.d(TAG, "Item: $item")
                val intent = Intent(context!!, DetailsActivity::class.java)
                intent.putExtra(DetailsActivity.RECORDEDPROGRAM, item)

                val bundle =
                    ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity!!,
                        (itemViewHolder?.view as ImageCardView).mainImageView,
                        DetailsActivity.SHARED_ELEMENT_NAME
                    )
                        .toBundle()
                startActivity(intent, bundle)
            }
        }
    }

    companion object {
        private const val TAG = "VideoDetailsFragment"

        internal const val ACTION_WATCH_ORIGINAL_TS = 0L

        private const val DETAIL_THUMB_WIDTH = 274
        private const val DETAIL_THUMB_HEIGHT = 274
    }
}