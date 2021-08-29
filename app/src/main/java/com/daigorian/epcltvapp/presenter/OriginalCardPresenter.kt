package com.daigorian.epcltvapp.presenter

import android.graphics.drawable.Drawable
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.ViewGroup

import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.daigorian.epcltvapp.R
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import com.daigorian.epcltvapp.epgstationcaller.GetRecordedParam
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.GetRecordedParamV2
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import kotlin.properties.Delegates

/**
 * A OriginalCardPresenter is used to generate Views and bind Objects to them on demand.
 * It contains an ImageCardView.
 */
class OriginalCardPresenter : Presenter() {
    private var mDefaultCardImage: Drawable? = null
    private var mOnRecordingCardImage: Drawable? = null

    private var sSelectedBackgroundColor: Int by Delegates.notNull()
    private var sDefaultBackgroundColor: Int by Delegates.notNull()

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
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cardView = viewHolder.view as ImageCardView
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)

        Log.d(TAG, "onBindViewHolder")
        when (item) {
            is RecordedProgram -> {
                // EPGStation Version 1.x.x
                cardView.titleText = item.name
                cardView.contentText = item.description
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
                cardView.contentText = item.description
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
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        Log.d(TAG, "onUnbindViewHolder")
        val cardView = viewHolder.view as ImageCardView
        // Remove references to images so that the garbage collector can free up memory
        cardView.badgeImage = null
        cardView.mainImage = null
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val color = if (selected) sSelectedBackgroundColor else sDefaultBackgroundColor
        // Both background colors should be set because the view"s background is temporarily visible
        // during animations.
        view.setBackgroundColor(color)
        view.setInfoAreaBackgroundColor(color)
    }

    companion object {
        private const val TAG = "OriginalCardPresenter"

        private const val CARD_WIDTH = 313
        private const val CARD_HEIGHT = 176
    }
}