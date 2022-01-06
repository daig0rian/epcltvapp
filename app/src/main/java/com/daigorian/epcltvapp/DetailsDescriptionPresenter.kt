package com.daigorian.epcltvapp

import android.graphics.Paint
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.daigorian.epcltvapp.epgstationcaller.*
import com.daigorian.epcltvapp.epgstationv2caller.*
import java.text.DateFormat
import java.util.*

class DetailsDescriptionPresenter : Presenter() {
    /**
     * The ViewHolder for the [DetailsDescriptionPresenter].
     */
    companion object {
        private const val TAG = "DetailsDescriptionPres."
        class ViewHolder(view: View) : Presenter.ViewHolder(view) {
            val title: TextView
            val subtitle: TextView
            val body: TextView
            val mTitleMargin: Int
            val mUnderTitleBaselineMargin: Int
            val mUnderSubtitleBaselineMargin: Int
            val mTitleLineSpacing: Int
            val mBodyLineSpacing: Int
            val mBodyMaxLines: Int
            val mBodyMinLines: Int
            val mTitleFontMetricsInt: Paint.FontMetricsInt
            val mSubtitleFontMetricsInt: Paint.FontMetricsInt
            val mBodyFontMetricsInt: Paint.FontMetricsInt
            val mTitleMaxLines: Int
            private var mPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
            fun addPreDrawListener() {
                if (mPreDrawListener != null) {
                    return
                }
                mPreDrawListener = ViewTreeObserver.OnPreDrawListener {
                    /*
                    if ((subtitle.visibility == View.VISIBLE
                                ) && (subtitle.top > view.height
                                ) && (title.lineCount > 1)
                    ) {
                        title.maxLines = title.lineCount - 1
                        return@OnPreDrawListener false
                    }
                    val titleLines = title.lineCount
                    val maxLines = if (titleLines > 1) mBodyMinLines else mBodyMaxLines
                    if (body.maxLines != maxLines) {
                        body.maxLines = maxLines
                        false
                    } else {
                        removePreDrawListener()
                        true
                    }
                    */
                    //小細工なしにとりあえず出せるだけ出してもらう。
                    title.maxLines = Int.MAX_VALUE
                    subtitle.maxLines = Int.MAX_VALUE
                    removePreDrawListener()
                    true
                }
                view.viewTreeObserver.addOnPreDrawListener(mPreDrawListener)
            }

            fun removePreDrawListener() {
                if (mPreDrawListener != null) {
                    view.viewTreeObserver.removeOnPreDrawListener(mPreDrawListener)
                    mPreDrawListener = null
                }
            }

            private fun getFontMetricsInt(textView: TextView): Paint.FontMetricsInt {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.textSize = textView.textSize
                paint.typeface = textView.typeface
                return paint.fontMetricsInt
            }

            init {
                title = view.findViewById<View>(R.id.lb_details_description_title) as TextView
                subtitle = view.findViewById<View>(R.id.lb_details_description_subtitle) as TextView
                body = view.findViewById<View>(R.id.lb_details_description_body) as TextView
                val titleFontMetricsInt = getFontMetricsInt(title)
                val titleAscent = view.resources.getDimensionPixelSize(
                    R.dimen.lb_details_description_title_baseline
                )
                // Ascent is negative
                mTitleMargin = titleAscent + titleFontMetricsInt.ascent
                mUnderTitleBaselineMargin = view.resources.getDimensionPixelSize(
                    R.dimen.lb_details_description_under_title_baseline_margin
                )
                mUnderSubtitleBaselineMargin = view.resources.getDimensionPixelSize(
                    R.dimen.lb_details_description_under_subtitle_baseline_margin
                )
                mTitleLineSpacing = view.resources.getDimensionPixelSize(
                    R.dimen.lb_details_description_title_line_spacing
                )
                mBodyLineSpacing = view.resources.getDimensionPixelSize(
                    R.dimen.lb_details_description_body_line_spacing
                )
                mBodyMaxLines = view.resources.getInteger(
                    R.integer.lb_details_description_body_max_lines
                )
                mBodyMinLines = view.resources.getInteger(
                    R.integer.lb_details_description_body_min_lines
                )
                mTitleMaxLines = title.maxLines
                mTitleFontMetricsInt = getFontMetricsInt(title)
                mSubtitleFontMetricsInt = getFontMetricsInt(subtitle)
                mBodyFontMetricsInt = getFontMetricsInt(body)
                title.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom -> addPreDrawListener() }
            }
        }
    }
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.lb_details_description, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any) {
        val vh = viewHolder as ViewHolder
        onBindDescription(vh, item)
        var hasTitle = true
        if (TextUtils.isEmpty(vh.title.text)) {
            vh.title.visibility = View.GONE
            hasTitle = false
        } else {
            vh.title.visibility = View.VISIBLE
            vh.title.setLineSpacing(
                vh.mTitleLineSpacing - vh.title.lineHeight
                        + vh.title.lineSpacingExtra, vh.title.lineSpacingMultiplier
            )
            vh.title.maxLines = vh.mTitleMaxLines
        }
        setTopMargin(vh.title, vh.mTitleMargin)
        var hasSubtitle = true
        if (TextUtils.isEmpty(vh.subtitle.text)) {
            vh.subtitle.visibility = View.GONE
            hasSubtitle = false
        } else {
            vh.subtitle.visibility = View.VISIBLE
            if (hasTitle) {
                setTopMargin(
                    vh.subtitle, vh.mUnderTitleBaselineMargin
                            + vh.mSubtitleFontMetricsInt.ascent - vh.mTitleFontMetricsInt.descent
                )
            } else {
                setTopMargin(vh.subtitle, 0)
            }
        }
        if (TextUtils.isEmpty(vh.body.text)) {
            vh.body.visibility = View.GONE
        } else {
            vh.body.visibility = View.VISIBLE
            vh.body.setLineSpacing(
                (vh.mBodyLineSpacing - vh.body.lineHeight
                        + vh.body.lineSpacingExtra), vh.body.lineSpacingMultiplier
            )
            if (hasSubtitle) {
                setTopMargin(
                    vh.body, vh.mUnderSubtitleBaselineMargin
                            + vh.mBodyFontMetricsInt.ascent - vh.mSubtitleFontMetricsInt.descent
                )
            } else if (hasTitle) {
                setTopMargin(
                    vh.body, vh.mUnderTitleBaselineMargin
                            + vh.mBodyFontMetricsInt.ascent - vh.mTitleFontMetricsInt.descent
                )
            } else {
                setTopMargin(vh.body, 0)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder?) {
        //DO Nothing
    }

    fun onBindDescription(
        viewHolder: ViewHolder,
        item: Any
    ) {
        val context = viewHolder.view.context

        val dfDateAndTime = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)
        val dfTime = DateFormat.getTimeInstance(DateFormat.SHORT)

        if ( item is RecordedProgram) {

            val startAt = dfDateAndTime.format(Date(item.startAt))
            val endAt = dfTime.format(Date(item.endAt))
            val duration =  (item.endAt - item.startAt) / 60 / 1000
            val recTimeInfo = context.getString(R.string.start_end_duration,startAt,endAt,duration)
            // 日本語 locale   : 2022年1月4日 14:00 ～ 14:30 (60分)
            // English locale : January 6, 2022 2:00 PM - 2:30 PM (60 min.)

            viewHolder.title.text = item.name
            viewHolder.subtitle.text = item.description.orEmpty()
            viewHolder.body.text = recTimeInfo + "\n" +
                    item.extended.orEmpty()

        }else if ( item is RecordedItem) {

            val startAt = dfDateAndTime.format(Date(item.startAt))
            val endAt = dfTime.format(Date(item.endAt))
            val duration =  (item.endAt - item.startAt) / 60 / 1000
            val recTimeInfo = context.getString(R.string.start_end_duration,startAt,endAt,duration)
            // 日本語 locale   : 2022年1月4日 14:00 ～ 14:30 (60分)
            // English locale : January 6, 2022 2:00 PM - 2:30 PM (60 min.)

            viewHolder.title.text = item.name
            viewHolder.subtitle.text = item.description.orEmpty()
            viewHolder.body.text = recTimeInfo + "\n" +
                    item.extended.orEmpty()
        }
    }

    override fun onViewDetachedFromWindow(holder: Presenter.ViewHolder) {
        val vh = holder as ViewHolder
        vh.removePreDrawListener()
        super.onViewDetachedFromWindow(holder)
    }

    private fun setTopMargin(textView: TextView, topMargin: Int) {
        val lp = textView.layoutParams as ViewGroup.MarginLayoutParams
        lp.topMargin = topMargin
        textView.layoutParams = lp
    }
}