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
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
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

    private lateinit var mDetailsBackground: DetailsSupportFragmentBackgroundController
    private lateinit var mPresenterSelector: ClassPresenterSelector
    private lateinit var mAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate DetailsFragment")
        super.onCreate(savedInstanceState)

        mDetailsBackground = DetailsSupportFragmentBackgroundController(this)

        mSelectedRecordedProgram = requireActivity().intent.getSerializableExtra(DetailsActivity.RECORDEDPROGRAM) as RecordedProgram
        if (mSelectedRecordedProgram != null) {
            mPresenterSelector = ClassPresenterSelector()
            mAdapter = ArrayObjectAdapter(mPresenterSelector)
            setupDetailsOverviewRow()
            setupDetailsOverviewRowPresenter()
            setupRelatedMovieListRow()
            adapter = mAdapter
            initializeBackground(mSelectedRecordedProgram!!)
            onItemViewClickedListener = ItemViewClickedListener()
        } else {
            val intent = Intent(requireContext(), MainActivity::class.java)
            startActivity(intent)
        }
    }

    private fun initializeBackground(recorded: RecordedProgram) {
        mDetailsBackground.enableParallax()
        Glide.with(requireContext())
            .asBitmap()
            .centerCrop()
            .error(R.drawable.default_background)
            .load(EpgStation.getThumbnailURL(recorded.id.toString()))
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
        Log.d(TAG, "doInBackground: " + mSelectedRecordedProgram?.toString())
        val row = DetailsOverviewRow(mSelectedRecordedProgram)
        row.imageDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.default_background)
        val width = convertDpToPixel(requireContext(), DETAIL_THUMB_WIDTH)
        val height = convertDpToPixel(requireContext(), DETAIL_THUMB_HEIGHT)
        Glide.with(requireContext())
            .load(EpgStation.getThumbnailURL(mSelectedRecordedProgram?.id.toString()))
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

            val playerId = Settings.getPLAYER_ID(requireContext())
            if( playerId == Settings.PLAYER_ID_INTERNAL) {
                //内蔵プレーヤー
                val intent = Intent(requireContext(), PlaybackActivity::class.java)
                intent.putExtra(DetailsActivity.RECORDEDPROGRAM, mSelectedRecordedProgram)
                intent.putExtra(DetailsActivity.ACTIONID, action.id)
                startActivity(intent)

            }else{
                //外部プレーヤー
                val urlStrings = if (action.id == ACTION_WATCH_ORIGINAL_TS) {
                    EpgStation.getTsVideoURL(mSelectedRecordedProgram?.id.toString())
                } else {
                    EpgStation.getEncodedVideoURL(mSelectedRecordedProgram?.id.toString(),action.id.toString())
                }

                val uri = Uri.parse(urlStrings)

                val extPlayerIntent = Intent(Intent.ACTION_VIEW)
                val extPlayerPackageName = Settings.PLAYER_ID_TO_PACKAGE[playerId]

                extPlayerIntent.setPackage(extPlayerPackageName)
                extPlayerIntent.setDataAndTypeAndNormalize(uri, "video/*")
                extPlayerIntent.putExtra("title", mSelectedRecordedProgram!!.name)

                try {
                    startActivity(extPlayerIntent)
                } catch (ex: ActivityNotFoundException) {
                    //外部プレーヤーがインストールされていないためGoogle Play Marketを表示
                    val marketIntent = Intent(Intent.ACTION_VIEW)
                    marketIntent.data = Uri.parse("market://details?id=$extPlayerPackageName")
                    startActivity(marketIntent)
                    Toast.makeText(requireContext(), getString(R.string.please_install_external_player), Toast.LENGTH_LONG).show()
                }
            }

        }
        mPresenterSelector.addClassPresenter(DetailsOverviewRow::class.java, detailsPresenter)
    }

    private fun setupRelatedMovieListRow() {

        //現在表示中の動画と同じルールIDを持った動画を検索してならべる。
        mSelectedRecordedProgram?.ruleId?.let{
            val listRowAdapter = ArrayObjectAdapter(CardPresenter())
            EpgStation.api.getRecorded(rule = mSelectedRecordedProgram?.ruleId).enqueue(object : Callback<GetRecordedResponse> {
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