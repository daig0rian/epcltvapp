package com.daigorian.epcltvapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
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
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import com.daigorian.epcltvapp.epgstationcaller.GetRecordedParam
import com.daigorian.epcltvapp.epgstationcaller.GetRecordedResponse
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.ApiErrorV2
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.GetRecordedParamV2
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import com.daigorian.epcltvapp.epgstationv2caller.Records
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
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
    private lateinit var mAdapter: DeleteEnabledArrayObjectAdapter
    private val mCardPresenter = OriginalCardPresenter()

    // 詳細行と、そこへ出しているサムネイルの元画像(南京錠を重ねる前のもの)。プロテクトの状態が
    // 変わったときに、画像を読み込み直さずに南京錠だけを出し入れするために控えておく。
    private var mOverviewRow: DetailsOverviewRow? = null
    private var mOverviewBaseImage: Drawable? = null

    // プロテクトの対象を取得している最中か。待っている間の連打でダイアログを二重に開かないために見る。
    private var mLoadingProtectTargets = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate DetailsFragment")
        super.onCreate(savedInstanceState)

        mDetailsBackground = DetailsSupportFragmentBackgroundController(this)

        // EPGStationのバージョンによってintentで渡されてくるオブジェクトタイプが違う。
        // EPGStation Version 1.x.x
        mSelectedRecordedProgram = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().intent.getSerializableExtra(DetailsActivity.RECORDEDPROGRAM, RecordedProgram::class.java)
        } else {
            @Suppress("DEPRECATION")
            requireActivity().intent.getSerializableExtra(DetailsActivity.RECORDEDPROGRAM) as RecordedProgram?
        }
        // EPGStation Version 2.x.x
        mSelectedRecordedItem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().intent.getSerializableExtra(DetailsActivity.RECORDEDITEM, RecordedItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            requireActivity().intent.getSerializableExtra(DetailsActivity.RECORDEDITEM) as RecordedItem?
        }

        when {
            mSelectedRecordedProgram != null -> {
                // EPGStation Version 1.x.x
                mPresenterSelector = ClassPresenterSelector()
                mAdapter = DeleteEnabledArrayObjectAdapter(mPresenterSelector)
                mCardPresenter.objAdapter = mAdapter
                setupDetailsOverviewRow()
                setupDetailsOverviewRowPresenter()
                updateRelatedMovieListRow()
                adapter = mAdapter
                initializeBackground(EpgStation.getThumbnailURL(mSelectedRecordedProgram?.id.toString()))
                onItemViewClickedListener = ItemViewClickedListener()
                setOnItemViewSelectedListener( ItemViewSelectedListener())
            }
            mSelectedRecordedItem != null -> {
                // EPGStation Version 2.x.x
                mPresenterSelector = ClassPresenterSelector()
                mAdapter = DeleteEnabledArrayObjectAdapter(mPresenterSelector)
                mCardPresenter.objAdapter = mAdapter
                setupDetailsOverviewRow()
                setupDetailsOverviewRowPresenter()
                updateRelatedMovieListRow()
                adapter = mAdapter
                initializeBackground(
                    EpgStationV2.getThumbnailURL(
                        if(mSelectedRecordedItem?.thumbnails?.isNotEmpty() == true)
                            {
                                mSelectedRecordedItem?.thumbnails?.get(0).toString()
                            }else{
                                ""
                            })
                )
                onItemViewClickedListener = ItemViewClickedListener()
                setOnItemViewSelectedListener( ItemViewSelectedListener())
            }
            else -> {
                val intent = Intent(requireContext(), MainActivity::class.java)
                startActivity(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateRelatedMovieListRow()
        refreshProtectedState()
    }

    private fun initializeBackground(imageURL: String) {
        mDetailsBackground.enableParallax()

        //Glideでイメージを取得する際にBasic認証が必要な場合はヘッダを付与してやる
        val glideUrl = if(EpgStation.api!=null && EpgStation.authForGlide!=null){
            GlideUrl( imageURL, EpgStation.authForGlide)
        }else if(EpgStationV2.api!=null && EpgStationV2.authForGlide!=null){
            GlideUrl( imageURL, EpgStationV2.authForGlide)
        }else{
            GlideUrl ( imageURL )
        }

        Glide.with(requireContext())
            .asBitmap()
            .centerCrop()
            .error(R.drawable.default_background)
            .load(glideUrl)
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
            // EPGStation Version 1.x.x
            DetailsOverviewRow(mSelectedRecordedProgram)
        }else {
            // EPGStation Version 2.x.x
            DetailsOverviewRow(mSelectedRecordedItem)
        }
        val urlString = if (mSelectedRecordedProgram!=null){
            // EPGStation Version 1.x.x
            EpgStation.getThumbnailURL(mSelectedRecordedProgram?.id.toString())
        }else {
            // EPGStation Version 2.x.x
            EpgStationV2.getThumbnailURL(
                if(mSelectedRecordedItem?.thumbnails?.isNotEmpty() == true)
                {
                    mSelectedRecordedItem?.thumbnails?.get(0).toString()
                }else{
                    ""
                })
        }

        mOverviewRow = row
        setOverviewImage(ContextCompat.getDrawable(requireContext(), R.drawable.default_background))
        val width = convertDpToPixel(requireContext(), DETAIL_THUMB_WIDTH)
        val height = convertDpToPixel(requireContext(), DETAIL_THUMB_HEIGHT)

        //Glideでイメージを取得する際にBasic認証が必要な場合はヘッダを付与してやる
        val glideUrl = if(EpgStation.api!=null && EpgStation.authForGlide!=null){
            GlideUrl( urlString, EpgStation.authForGlide)
        }else if(EpgStationV2.api!=null && EpgStationV2.authForGlide!=null){
            GlideUrl( urlString, EpgStationV2.authForGlide)
        }else{
            GlideUrl ( urlString )
        }

        Glide.with(requireContext())
            .load(glideUrl)
            .centerCrop()
            .error(R.drawable.default_background)
            .into<CustomTarget<Drawable>>(object : CustomTarget<Drawable>(width, height) {
                override fun onResourceReady(
                    drawable: Drawable,
                    transition: Transition<in Drawable>?
                ) {
                    Log.d(TAG, "details overview card image url ready: $drawable")
                    setOverviewImage(drawable)
                    mAdapter.notifyArrayItemRangeChanged(0, mAdapter.size())
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    //サムネのロードが失敗したので、録画中かどうか調べてだし分けする。
                    super.onLoadFailed(errorDrawable)
                    Log.d(TAG, "details overview card image url read on fail: $errorDrawable")
                    mSelectedRecordedProgram?.let {
                        // EPGStation Version 1.x.x
                        if (it.recording) {
                            setOverviewImage(
                                ContextCompat.getDrawable(context!!, R.drawable.on_rec))
                        } else{
                            setOverviewImage(
                                ContextCompat.getDrawable(context!!, R.drawable.no_iamge))
                        }
                    }
                    mSelectedRecordedItem?.let{
                        // EPGStation Version 2.x.x
                        if (it.isRecording){
                            setOverviewImage(
                                ContextCompat.getDrawable(context!!, R.drawable.on_rec))
                        } else{
                            setOverviewImage(
                                ContextCompat.getDrawable(context!!, R.drawable.no_iamge))
                        }
                    }
                    mAdapter.notifyArrayItemRangeChanged(0, mAdapter.size())
                }
                override fun onLoadCleared( placeholder: Drawable?) {}
            })

        val actionAdapter = ArrayObjectAdapter()

        mSelectedRecordedProgram?.let {
            // EPGStation Version 1.x.x
            // オリジナルのTSがある場合は "TS を再生" アクションアダプタを追加
            // (収録中は「追いかけ再生」の意味を持つラベルにする。収録済みなら通常の再生ラベル)
            if (it.original) {
                actionAdapter.add(
                    Action(
                        ACTION_WATCH_ORIGINAL_TS,
                        if (it.recording) getString(R.string.play_ts) else getString(R.string.play_original_ts)
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
            // EPGStation Version 2.x.x
            recordedItem.videoFiles?.forEach { videoFIle ->
                actionAdapter.add(
                    Action(
                        videoFIle.id,
                        // 収録中のtsファイルのみ「追いかけ再生」の意味を持つラベルにする。
                        // 収録済みなら従来通りファイル名ベースの通常の再生ラベル。
                        if (videoFIle.type == "ts" && recordedItem.isRecording) {
                            getString(R.string.play_ts)
                        } else {
                            getString(R.string.play_x, videoFIle.name)
                        }
                    )
                )
            }
            // 録画中の TS ファイルがある場合、内蔵プレーヤー選択時のみ追いかけ再生ボタンを追加
            val playerPkgName = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString(getString(R.string.pref_key_player), "")
            if (recordedItem.isRecording &&
                recordedItem.videoFiles?.any { it.type == "ts" } == true &&
                playerPkgName == getString(R.string.pref_options_movie_player_val_INTERNAL)
            ) {
                actionAdapter.add(
                    Action(ACTION_WATCH_RECORDING_HLS, getString(R.string.play_catchup_hls))
                )
            }
        }



        actionAdapter.add(Action(ACTION_SHOW_DESCRIPTION, getString(R.string.program_info)))

        // プロテクトを切り替える API は EPGStation v2 にしか無い。v1 は状態を読めるだけなので出さない。
        if (mSelectedRecordedItem != null) {
            actionAdapter.add(Action(ACTION_PROTECT, getString(R.string.protect)))
        }

        row.actionsAdapter = actionAdapter

        mAdapter.add(row)
    }

    private fun setupDetailsOverviewRowPresenter() {
        Log.d(TAG, "setupDetailsOverviewRowPresenter()")

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

            if (action.id == ACTION_SHOW_DESCRIPTION) {
                val jst = TimeZone.getTimeZone("Asia/Tokyo")
                val dfDateAndTime = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).also { it.timeZone = jst }
                val dfTime = DateFormat.getTimeInstance(DateFormat.SHORT).also { it.timeZone = jst }
                val (programName, bodyText) = when {
                    mSelectedRecordedProgram != null -> {
                        val it = mSelectedRecordedProgram!!
                        val channelName = EpgStation.channelMap[it.channelId] ?: ""
                        val genreText = AribGenre.getGenreText(it.genre1, null)
                        val recTimeInfo = getString(R.string.start_end_duration,
                            dfDateAndTime.format(Date(it.startAt)),
                            dfTime.format(Date(it.endAt)),
                            (it.endAt - it.startAt) / 60 / 1000)
                        val body = buildString {
                            if (channelName.isNotEmpty()) { append(channelName); append("\n") }
                            if (genreText.isNotEmpty()) { append(genreText); append("\n") }
                            append(recTimeInfo)
                            if (!it.description.isNullOrEmpty()) { append("\n\n"); append(it.description) }
                            if (!it.extended.isNullOrEmpty()) { append("\n"); append(it.extended) }
                        }
                        Pair(it.name, body)
                    }
                    mSelectedRecordedItem != null -> {
                        val it = mSelectedRecordedItem!!
                        val channelName = it.channelId?.let { id -> EpgStationV2.channelMap[id] } ?: ""
                        val genreText = AribGenre.getGenreText(it.genre1, it.subGenre1)
                        val recTimeInfo = getString(R.string.start_end_duration,
                            dfDateAndTime.format(Date(it.startAt)),
                            dfTime.format(Date(it.endAt)),
                            (it.endAt - it.startAt) / 60 / 1000)
                        val body = buildString {
                            if (channelName.isNotEmpty()) { append(channelName); append("\n") }
                            if (genreText.isNotEmpty()) { append(genreText); append("\n") }
                            append(recTimeInfo)
                            if (!it.description.isNullOrEmpty()) { append("\n\n"); append(it.description) }
                            if (!it.extended.isNullOrEmpty()) { append("\n"); append(it.extended) }
                        }
                        Pair(it.name, body)
                    }
                    else -> return@OnActionClickedListener
                }
                ProgramInfoDialogFragment.newInstance(programName, bodyText)
                    .show(childFragmentManager, ProgramInfoDialogFragment.TAG)
                return@OnActionClickedListener
            }

            if (action.id == ACTION_PROTECT) {
                showProtectDialog()
                return@OnActionClickedListener
            }

            if (action.id == ACTION_WATCH_RECORDING_HLS) {
                val tsFile = mSelectedRecordedItem?.videoFiles?.firstOrNull { it.type == "ts" }
                    ?: return@OnActionClickedListener
                val intent = Intent(requireContext(), PlaybackActivity::class.java)
                mSelectedRecordedItem?.let { intent.putExtra(DetailsActivity.RECORDEDITEM, it) }
                intent.putExtra(DetailsActivity.ACTIONID, tsFile.id)
                intent.putExtra(DetailsActivity.IS_HLS, true)
                startActivity(intent)
                return@OnActionClickedListener
            }

            val playerPkgName = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(getString(R.string.pref_key_player),"")
            if( playerPkgName == getString(R.string.pref_options_movie_player_val_INTERNAL)) {
                //Preferenceで内蔵プレーヤーが選ばれていた場合
                val intent = Intent(requireContext(), PlaybackActivity::class.java)
                //EPGStation Version 1.x.x
                mSelectedRecordedProgram?.let{intent.putExtra(DetailsActivity.RECORDEDPROGRAM, mSelectedRecordedProgram)}
                //EPGStation Version 2.x.x
                mSelectedRecordedItem?.let{intent.putExtra(DetailsActivity.RECORDEDITEM, mSelectedRecordedItem)}
                intent.putExtra(DetailsActivity.ACTIONID, action.id)
                // TS コンテンツかどうかを判定して渡す
                val isTsContent = if (mSelectedRecordedProgram != null) {
                    action.id == ACTION_WATCH_ORIGINAL_TS
                } else {
                    mSelectedRecordedItem?.videoFiles?.find { it.id == action.id }?.type == "ts"
                }
                intent.putExtra(DetailsActivity.IS_TS_CONTENT, isTsContent)
                startActivity(intent)

            }else {
                // Preferenceで外部プレーヤーが選ばれていた場合
                val urlStrings = if (mSelectedRecordedProgram != null) {
                    // EPGStation Version 1.x.x
                    if (action.id == ACTION_WATCH_ORIGINAL_TS) {
                        // TSだった場合
                        EpgStation.getTsVideoURL(mSelectedRecordedProgram?.id.toString())
                    } else {
                        // Encodedだった場合
                        EpgStation.getEncodedVideoURL(
                            mSelectedRecordedProgram?.id.toString(),
                            action.id.toString()  )
                    }
                }else{
                    // EPGStation Version 2.x.x
                    EpgStationV2.getVideoURL(action.id.toString())
                }

                val uri = Uri.parse(urlStrings)
                val extPlayerIntent = Intent(Intent.ACTION_VIEW)
                extPlayerIntent.setPackage(playerPkgName)
                extPlayerIntent.setDataAndTypeAndNormalize(uri, "video/*")
                // EPGStation Version 1.x.x
                mSelectedRecordedProgram?.let{extPlayerIntent.putExtra("title", mSelectedRecordedProgram?.name)}
                // EPGStation Version 2.x.x
                mSelectedRecordedItem?.let{extPlayerIntent.putExtra("title", mSelectedRecordedItem?.name)}

                try {
                    startActivity(extPlayerIntent)
                } catch (ex: ActivityNotFoundException) {
                    //外部プレーヤーがインストールされていなからインストールしてねのメッセージを表示
                    Toast.makeText(requireContext(), getString(R.string.please_install_external_player), Toast.LENGTH_LONG).show()
                    try{
                        //Google Play Storeで外部プレイヤーのページを表示
                        val marketIntent = Intent(Intent.ACTION_VIEW)
                        marketIntent.data = Uri.parse("market://details?id=$playerPkgName")
                        startActivity(marketIntent)
                    } catch (ex: ActivityNotFoundException) {
                        //Google Play Storeすら導入されていないからどうしようもできない。
                    }
                }
            }

        }
        mPresenterSelector.addClassPresenter(DetailsOverviewRow::class.java, detailsPresenter)
    }

    /** 今開いている録画がプロテクト済みか。 */
    private fun isSelectedProtected(): Boolean =
        mSelectedRecordedProgram?.protection ?: mSelectedRecordedItem?.isProtected ?: false

    /**
     * 詳細行のサムネイルを差し替える。プロテクト済みなら南京錠を重ねる。
     *
     * 大きさを持たない画像(読み込みを待つ間の仮の背景)には重ねない。南京錠は画像の大きさに
     * 合わせて描くので、読み込み後とは違う位置・大きさで一瞬出てしまう。
     */
    private fun setOverviewImage(image: Drawable?) {
        mOverviewBaseImage = image
        val context = context
        mOverviewRow?.imageDrawable = if (
            image != null && context != null && isSelectedProtected() &&
            image.intrinsicWidth > 0 && image.intrinsicHeight > 0
        ) {
            LayerDrawable(arrayOf(image, ProtectedBadgeDrawable(context, PROTECTED_BADGE_SIZE_RATIO)))
        } else {
            image
        }
    }

    /**
     * 今開いている録画のプロテクト状態を [isProtected] に合わせ、画面の表示へ反映する。
     * v2 専用(v1 にはこのアプリから状態を変える手段が無い)。
     */
    private fun applyProtectedState(isProtected: Boolean) {
        val item = mSelectedRecordedItem ?: return
        if (item.isProtected == isProtected) return
        val updated = item.copy(isProtected = isProtected)
        mSelectedRecordedItem = updated
        // 画面が作り直されたときに古い状態へ戻らないよう、起動時に受け取った引数も差し替える。
        activity?.intent?.putExtra(DetailsActivity.RECORDEDITEM, updated)
        mOverviewRow?.item = updated
        setOverviewImage(mOverviewBaseImage)
    }

    /**
     * 今開いている録画のプロテクト状態を取り直す。
     *
     * 一覧から受け取った状態は古いことがあり、この画面を開いている間にも変わりうる——
     * 関連動画から開いた別の回でシリーズごとプロテクトして戻ってきた場合など。関連動画の
     * カードは取り直しで更新されるので、こちらだけ古いままだと同じ録画なのに南京錠の有無が
     * 食い違う。
     */
    private fun refreshProtectedState() {
        val item = mSelectedRecordedItem ?: return
        EpgStationV2.api?.getRecordedItem(item.id)?.enqueue(object : Callback<RecordedItem> {
            override fun onResponse(call: Call<RecordedItem>, response: Response<RecordedItem>) {
                if (!isAdded) return
                response.body()?.let { applyProtectedState(it.isProtected) }
            }

            override fun onFailure(call: Call<RecordedItem>, t: Throwable) {
                // 取り直せなくても、受け取った時点の状態でこの画面は使える。
                // 接続に失敗したことは、同時に走る関連動画の取得のほうが知らせる。
                Log.d(TAG, "refreshProtectedState() getRecordedItem API Failure")
            }
        })
    }

    /**
     * プロテクトの対象を選ぶダイアログを開く。
     *
     * 開く前にシリーズの全件を取りに行く。シリーズの本数を選択肢に書いて見せるためと、
     * 選ばれたときに要求を送る先をこの時点で確定させるため。
     */
    private fun showProtectDialog() {
        val item = mSelectedRecordedItem ?: return
        if (mLoadingProtectTargets) return
        mLoadingProtectTargets = true
        SeriesPlaylist.load(null, item, fetchAll = true) { series ->
            mLoadingProtectTargets = false
            if (!isAdded || isStateSaved) return@load
            // 選択肢はシリーズの一覧から組むので、単体の側もそれと同じ時点の状態に揃える。
            series?.entries?.firstOrNull { it.id == item.id }
                ?.let { applyProtectedState(it.isProtected) }
            val current = mSelectedRecordedItem ?: return@load
            val choices = ProtectOperation.choicesFor(
                current.id, current.name, current.isProtected, series
            )
            ProtectDialogFragment.newInstance(choices)
                .show(childFragmentManager, ProtectDialogFragment.TAG)
        }
    }

    /**
     * [ProtectDialogFragment] で選ばれた操作を実行する。
     *
     * EPGStation にまとめて変える API は無いので、対象の本数だけ要求を送り、全部の結果が
     * 揃ってから1回だけ結果を知らせる。
     */
    fun onProtectOperationChosen(operation: ProtectOperation) {
        val api = EpgStationV2.api ?: return
        val succeededIds = mutableListOf<Long>()
        var pending = operation.idsToChange.size
        // 結果はすべてメインスレッドへ返るので、数える側に排他は要らない。
        fun onOneFinished() {
            pending -= 1
            if (pending == 0) onProtectOperationFinished(operation, succeededIds)
        }
        operation.idsToChange.forEach { id ->
            val request = if (operation.protect) api.protectRecorded(id) else api.unprotectRecorded(id)
            request.enqueue(object : Callback<ApiErrorV2> {
                override fun onResponse(call: Call<ApiErrorV2>, response: Response<ApiErrorV2>) {
                    if (response.isSuccessful) {
                        succeededIds.add(id)
                    } else {
                        Log.w(TAG, "protect=${operation.protect} failed: id=$id HTTP${response.code()}")
                    }
                    onOneFinished()
                }

                override fun onFailure(call: Call<ApiErrorV2>, t: Throwable) {
                    Log.w(TAG, "protect=${operation.protect} failed: id=$id ${t.message}")
                    onOneFinished()
                }
            })
        }
    }

    private fun onProtectOperationFinished(operation: ProtectOperation, succeededIds: List<Long>) {
        if (!isAdded) return
        val failedCount = operation.idsToChange.size - succeededIds.size
        val message = when {
            failedCount == 0 && operation.isSeries -> getString(
                if (operation.protect) R.string.protect_series_done else R.string.unprotect_series_done,
                operation.totalCount
            )
            failedCount == 0 -> getString(
                if (operation.protect) R.string.protect_done else R.string.unprotect_done
            )
            operation.isSeries -> getString(
                R.string.protect_partially_failed, operation.totalCount, failedCount
            )
            else -> getString(R.string.protect_failed)
        }
        Toast.makeText(
            requireContext(), message,
            if (failedCount == 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        ).show()

        val currentId = mSelectedRecordedItem?.id
        if (currentId != null && currentId in succeededIds) applyProtectedState(operation.protect)
        // 関連動画のカードに出ている南京錠も合わせる。
        updateRelatedMovieListRow()
    }


    private fun updateRelatedMovieListRow() {
        Log.d(TAG, "updateRelatedMovieListRow()")
        // 関連動画一覧の生成
        //  - 現在表示中の動画の名前からシリーズ名を取り出し、それをキーワードに検索して一覧を表示
        //    第1話から順に追えるよう、放送日の古い順 (reverse) で並べる
        //  - 現在表示中の動画と同じルールIDを持った動画を検索して一覧を表示
        //    最近録れたものを拾う一覧なので、放送日の新しい順 (APIの既定) のまま
        //    ただしルールを使わず個別に録画したものはルールIDを持たないため、この一覧は出さない

        // 番組名 originalTitle
        val originalTitle :String =if(mSelectedRecordedProgram!=null) mSelectedRecordedProgram!!.name
        else mSelectedRecordedItem!!.name

        val searchKeyword = SeriesTitleExtractor.extract(originalTitle)
        if (searchKeyword.isNotEmpty()) {
            mAdapter.updateContentsListRow(
                GetRecordedParam(keyword = searchKeyword, reverse = true),
                GetRecordedParamV2(keyword = searchKeyword, isReverse = true),
                searchKeyword,
                0,
                mCardPresenter,
                requireContext()
            )
        }

        val ruleId =if(mSelectedRecordedProgram!=null) mSelectedRecordedProgram!!.ruleId
        else mSelectedRecordedItem!!.ruleId

        // 番組表やライブ再生から個別に録画した番組はルールIDを持たない。そのまま検索すると
        // ルールでの絞り込みが効かず、全ルールの録画 (＝「最近の録画」と同じ内容) が並んでしまう。
        if (ruleId != null && ruleId != 0L) {
            mAdapter.updateContentsListRow(
                GetRecordedParam(rule = ruleId),
                GetRecordedParamV2(ruleId = ruleId),
                getString(R.string.videos_in_same_rule),
                1,
                mCardPresenter,
                requireContext()
            )
        }
        mPresenterSelector.addClassPresenter(ListRow::class.java, ListRowPresenter())

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
                //EPGStation Version 1.x.x
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
            } else if (item is RecordedItem) {
                //EPGStation Version 2.x.x
                Log.d(TAG, "Item: $item")
                val intent = Intent(context!!, DetailsActivity::class.java)
                intent.putExtra(DetailsActivity.RECORDEDITEM, item)

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

    private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
        override fun onItemSelected(
            itemViewHolder: Presenter.ViewHolder?, item: Any?,
            rowViewHolder: RowPresenter.ViewHolder, row: Row
        ) {
            when (item) {
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
                            Log.d(TAG,"onItemSelected() getRecorded API Failure")
                            if (isAdded) Toast.makeText(requireContext(), getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
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
                            Log.d(TAG,"onItemSelected() getRecorded API Failure")
                            if (isAdded) Toast.makeText(requireContext(), getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
                        }
                    })
                }

            }
        }
    }

    companion object {
        private const val TAG = "VideoDetailsFragment"

        internal const val ACTION_WATCH_ORIGINAL_TS = 0L
        internal const val ACTION_SHOW_DESCRIPTION = -1L
        internal const val ACTION_WATCH_RECORDING_HLS = -2L
        internal const val ACTION_PROTECT = -3L

        private const val DETAIL_THUMB_WIDTH = 274
        private const val DETAIL_THUMB_HEIGHT = 274

        /** サムネイルに重ねる南京錠(下敷きの円)の直径。サムネイルの高さに対する比。 */
        private const val PROTECTED_BADGE_SIZE_RATIO = 0.16f
    }
}
