package com.daigorian.epcltvapp

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.leanback.R as LeanbackR
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.daigorian.epcltvapp.epgstationcaller.*
import com.daigorian.epcltvapp.epgstationv2caller.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.properties.Delegates

/**
 * A OriginalCardPresenter is used to generate Views and bind Objects to them on demand.
 * It contains an ImageCardView.
 */
class OriginalCardPresenter() : Presenter() {
    private var mDefaultCardImage: Drawable? = null
    private var mOnRecordingCardImage: Drawable? = null

    private var sSelectedBackgroundColor: Int by Delegates.notNull()
    private var sDefaultBackgroundColor: Int by Delegates.notNull()

    var objAdapter :DeleteEnabledArrayObjectAdapter? =null

    /**
     * 今再生中の録画のID。セットすると、そのカードのサムネイルに「再生中」の目印を重ねる。
     * 再生画面のエピソード一覧だけで使い、他の一覧(ホーム・検索・詳細)では null のまま。
     */
    var nowPlayingId: Long? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        Log.d(TAG, "onCreateViewHolder")

        sDefaultBackgroundColor = ContextCompat.getColor(parent.context, R.color.default_background)
        sSelectedBackgroundColor =
            ContextCompat.getColor(parent.context, R.color.selected_background)
        mDefaultCardImage = ContextCompat.getDrawable(parent.context, R.drawable.no_iamge)
        mOnRecordingCardImage = ContextCompat.getDrawable(parent.context, R.drawable.on_rec)

        val cardView = object : ImageCardView(parent.context) {
            override fun setSelected(selected: Boolean) {
                updateCardBackgroundColor(this, selected)
                super.setSelected(selected)
            }
        }

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        updateCardBackgroundColor(cardView, false)
        cardView.findViewById<TextView>(LeanbackR.id.title_text).maxLines = 3
        cardView.findViewById<TextView>(LeanbackR.id.content_text).maxLines = 4
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cardView = viewHolder.view as ImageCardView
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)

        viewHolder.view.setOnLongClickListener{
            if (item is ChannelItem) {
                // 長押しでHLS再生（通常タップはmpegts直送）。サーバー側で変換済みの映像を
                // 受け取るため、mpegts直送が不安定な場合や回線・端末性能が足りない場合の
                // フォールバック用途。なおARIB字幕・文字スーパー・デュアルモノ副音声は
                // TSを直接扱う直送側(isTsContent=true)でのみ扱えるため、HLSでは表示できない。
                val intent = Intent(it.context, PlaybackActivity::class.java)
                intent.putExtra(DetailsActivity.IS_LIVE, true)
                intent.putExtra(DetailsActivity.CHANNEL_ID, item.id)
                intent.putExtra(DetailsActivity.CHANNEL_NAME, item.halfWidthName.ifEmpty { item.name })
                it.context.startActivity(intent)
                return@setOnLongClickListener true
            }

            AlertDialog.Builder(it.context, AppCompatR.style.Theme_AppCompat_Light_Dialog_MinWidth)
                .setTitle(it.context.getString(R.string.do_you_want_to_delete,cardView.titleText.toString()))
                .setPositiveButton(it.context.getString(R.string.delete)) { dialog, which ->
                    when (item) {
                        is RecordedProgram -> {
                            // EPGStation Version 1.x.x のアイテム
                            EpgStation.api?.deleteRecorded(
                                item.id
                            )?.enqueue(object : Callback<ApiError> {
                                override fun onResponse(
                                    call: Call<ApiError>,
                                    response: Response<ApiError>
                                ) {
                                    if (response.body() != null &&
                                        response.body()!!.code != null &&
                                        response.body()!!.code == 200L
                                    ) {
                                        Toast.makeText(it.context, it.context.getString(R.string.successfully_deleted), Toast.LENGTH_SHORT)
                                            .show()
                                        objAdapter?.removeItemFromAllListRows(item)
                                    } else {
                                        Toast.makeText(it.context, it.context.getString(R.string.delete_failed), Toast.LENGTH_LONG)
                                            .show()
                                    }
                                }

                                override fun onFailure(
                                    call: Call<ApiError>,
                                    t: Throwable
                                ) {
                                    Toast.makeText(it.context, it.context.getString(R.string.delete_failed), Toast.LENGTH_LONG)
                                        .show()
                                }
                            })
                        }
                        is RecordedItem -> {
                            // EPGStation Version 2.x.x のアイテム
                            EpgStationV2.api?.deleteRecorded(
                                item.id
                            )?.enqueue(object : Callback<ApiErrorV2> {
                                override fun onResponse(
                                    call: Call<ApiErrorV2>,
                                    response: Response<ApiErrorV2>
                                ) {
                                    if (response.body() != null &&
                                        response.body()!!.code != null &&
                                        response.body()!!.code == 200L
                                    ) {
                                        Toast.makeText(it.context, it.context.getString(R.string.successfully_deleted), Toast.LENGTH_SHORT)
                                            .show()
                                        objAdapter?.removeItemFromAllListRows(item)
                                    } else {
                                        Toast.makeText(it.context, it.context.getString(R.string.delete_failed), Toast.LENGTH_LONG)
                                            .show()
                                    }
                                }

                                override fun onFailure(
                                    call: Call<ApiErrorV2>,
                                    t: Throwable
                                ) {
                                    Toast.makeText(it.context, it.context.getString(R.string.delete_failed), Toast.LENGTH_LONG)
                                        .show()
                                }
                            })
                        }
                    }


                }
                .setNegativeButton(it.context.getString(R.string.cancel)) { dialogInterface, i ->
                    // User chose NO
                }.create().show()
            true
        }

        Log.d(TAG, "onBindViewHolder")
        when (item) {
            is RecordedProgram -> {
                // EPGStation Version 1.x.x
                cardView.titleText = item.name
                val channelNameV1 = EpgStation.channelMap[item.channelId] ?: ""
                cardView.contentText = buildString {
                    if (channelNameV1.isNotEmpty()) { append(channelNameV1); append("\n") }
                    append("${formatAsJapaneseDateTime(item.startAt)}〜 (${formatDurationMinutes(item.startAt, item.endAt)})")
                    if (!item.description.isNullOrEmpty()) { append("\n"); append(item.description) }
                }
                val thumbnailURL = EpgStation.getThumbnailURL(item.id.toString())

                //Glideでイメージを取得する際にBasic認証が必要な場合はヘッダを付与してやる
                val glideUrl = if(EpgStation.authForGlide!=null){
                    GlideUrl ( thumbnailURL, EpgStation.authForGlide)
                }else{
                    GlideUrl ( thumbnailURL)
                }
                //録画中なら録画中アイコンを出す。
                Glide.with(viewHolder.view.context)
                    .load(glideUrl)
                    .centerCrop()
                    .error(if(item.recording){mOnRecordingCardImage}else{mDefaultCardImage})
                    .into(cardView.mainImageView)

            }
            is RecordedItem -> {
                // EPGStation Version 2.x.x
                cardView.titleText = item.name
                val channelNameV2 = item.channelId?.let { EpgStationV2.channelMap[it] } ?: ""
                cardView.contentText = buildString {
                    if (channelNameV2.isNotEmpty()) { append(channelNameV2); append("\n") }
                    append("${formatAsJapaneseDateTime(item.startAt)}〜 (${formatDurationMinutes(item.startAt, item.endAt)})")
                    if (!item.description.isNullOrEmpty()) { append("\n"); append(item.description) }
                }
                //サムネのURLから画像をロードする。失敗した場合、録画中なら録画中アイコンを出す。そうでなければNO IMAGEアイコン。

                val thumbnailURL = if(!item.thumbnails.isNullOrEmpty()){
                    EpgStationV2.getThumbnailURL(item.thumbnails[0].toString())
                }else{
                    EpgStationV2.getThumbnailURL("") // ありえないURL。必ず.error()になる。
                }
                //Glideでイメージを取得する際にBasic認証が必要な場合はヘッダを付与してやる
                val glideUrl = if(EpgStationV2.authForGlide!=null){
                    GlideUrl ( thumbnailURL, EpgStationV2.authForGlide)
                }else{
                    GlideUrl ( thumbnailURL)
                }

                Glide.with(viewHolder.view.context)
                    .load(glideUrl)
                    .centerCrop()
                    .error(if(item.isRecording){mOnRecordingCardImage}else{mDefaultCardImage})
                    .into(cardView.mainImageView)
            }
            is ChannelItem -> {
                // ライブ視聴用のチャンネル一覧アイテム
                cardView.titleText = item.halfWidthName.ifEmpty { item.name }
                val startAt = item.currentProgramStartAt
                val endAt = item.currentProgramEndAt
                cardView.contentText = buildString {
                    if (startAt != null && endAt != null) {
                        append(formatTimeRange(startAt, endAt))
                        append("\n")
                    }
                    append(item.currentProgramName ?: "")
                }

                if (item.hasLogoData) {
                    val logoURL = EpgStationV2.getChannelLogoURL(item.id)
                    //Glideでイメージを取得する際にBasic認証が必要な場合はヘッダを付与してやる
                    val glideUrl = if (EpgStationV2.authForGlide != null) {
                        GlideUrl(logoURL, EpgStationV2.authForGlide)
                    } else {
                        GlideUrl(logoURL)
                    }
                    Glide.with(viewHolder.view.context)
                        .load(glideUrl)
                        .fitCenter()
                        .error(mDefaultCardImage)
                        .into(cardView.mainImageView)
                } else {
                    Glide.with(viewHolder.view.context).clear(cardView.mainImageView)
                    cardView.mainImage = mDefaultCardImage
                }
            }
            is GetRecordedParam -> {
                // EPGStation Version 1.x.x の先を読み込むBOX。ただ黒いBOX
                cardView.titleText = ""
                cardView.contentText = ""
            }
            is GetRecordedParamV2 -> {
                // EPGStation Version 2.x.x の先を読み込むBOX。ただ黒いBOX
                cardView.titleText = ""
                cardView.contentText = ""
            }
        }

        updateNowPlayingOverlay(cardView, item)
    }

    /**
     * 「再生中」の目印を出し入れする。カードは使い回されるので、毎回どちらかを必ず行う。
     *
     * [ImageCardView] は子ビューを縦に積む [androidx.leanback.widget.BaseCardView] 派生で、
     * 重ねるビューを足せない。サムネイルの ViewOverlay に載せることで、レイアウトへ手を
     * 入れずに画像の上へ描く。
     */
    private fun updateNowPlayingOverlay(cardView: ImageCardView, item: Any) {
        val itemId = when (item) {
            is RecordedProgram -> item.id
            is RecordedItem -> item.id
            else -> null
        }
        val overlay = cardView.mainImageView?.overlay ?: return
        overlay.clear()
        if (itemId == null || itemId != nowPlayingId) return
        overlay.add(
            NowPlayingOverlayDrawable(cardView.context.getString(R.string.now_playing)).apply {
                // setMainImageDimensions() でこのサイズに固定してあるので、実測を待たずに置ける。
                setBounds(0, 0, CARD_WIDTH, CARD_HEIGHT)
            }
        )
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        Log.d(TAG, "onUnbindViewHolder")
        val cardView = viewHolder.view as ImageCardView
        // Remove references to images so that the garbage collector can free up memory
        cardView.badgeImage = null
        cardView.mainImage = null
        cardView.mainImageView?.overlay?.clear()
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val color = if (selected) sSelectedBackgroundColor else sDefaultBackgroundColor
        // Both background colors should be set because the view"s background is temporarily visible
        // during animations.
        view.setBackgroundColor(color)
        view.setInfoAreaBackgroundColor(color)
    }

    private fun formatAsJapaneseDateTime(unixTimeMs: Long): String {
        val formatter = SimpleDateFormat("MM/dd(EEE) HH:mm", Locale.JAPANESE)
        formatter.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        return formatter.format(Date(unixTimeMs))
    }

    private fun formatDurationMinutes(startUnixTimeMs: Long, endUnixTimeMs: Long): String {
        val minutes = (endUnixTimeMs - startUnixTimeMs) / 60000
        return "${minutes}分"
    }

    /** 番組の開始〜終了時刻を0埋め24時間表示（例: 19:00〜19:54）で返す */
    private fun formatTimeRange(startUnixTimeMs: Long, endUnixTimeMs: Long): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.JAPAN)
        formatter.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        return "${formatter.format(Date(startUnixTimeMs))}〜${formatter.format(Date(endUnixTimeMs))}"
    }

    companion object {
        private const val TAG = "OriginalCardPresenter"

        private const val CARD_WIDTH = 313
        private const val CARD_HEIGHT = 176
    }
}

/**
 * 再生中のカードのサムネイルに重ねる目印。サムネイル全体を半透明の黒で覆い、
 * 中央に白抜きで文言を出す。
 */
private class NowPlayingOverlayDrawable(private val label: String) : Drawable() {
    private val scrimPaint = Paint().apply { color = Color.argb(SCRIM_ALPHA, 0, 0, 0) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.drawRect(bounds, scrimPaint)

        textPaint.textSize = bounds.height() * TEXT_SIZE_RATIO
        // 訳語が長い言語でもカードからはみ出さないよう、幅に収まるところまで縮める。
        val maxWidth = bounds.width() * TEXT_MAX_WIDTH_RATIO
        val measured = textPaint.measureText(label)
        if (measured > maxWidth) textPaint.textSize *= maxWidth / measured

        // 文字の見た目の中心を高さの中心へ合わせる(baseline は文字の下端ではない)。
        val metrics = textPaint.fontMetrics
        val baselineY = bounds.exactCenterY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, bounds.exactCenterX(), baselineY, textPaint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** 黒を50%(255の半分)の濃さで重ねる */
        private const val SCRIM_ALPHA = 128
        private const val TEXT_SIZE_RATIO = 0.22f
        private const val TEXT_MAX_WIDTH_RATIO = 0.8f
    }
}