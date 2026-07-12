package com.daigorian.epcltvapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter

class SettingsCardPresenter : Presenter() {

    data class Item(
        @DrawableRes val iconRes: Int,
        val label: String,
        val action: Action,
        /** iconRes の代わりに文字（絵文字・記号1文字など）をアイコンとして描画したい場合に指定する */
        val iconChar: String? = null
    ) {
        enum class Action {
            CONNECTION, PLAYER, DISPLAY, RELOAD, WAKE_ON_LAN, CUSTOM_URL
        }
    }

    /** iconChar で指定された1文字をアイコン領域に中央揃えで描画するだけの簡易Drawable */
    private class TextIconDrawable(private val text: String) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        override fun draw(canvas: Canvas) {
            paint.textSize = bounds.height() * 0.8f
            val y = bounds.centerY() - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(text, bounds.centerX().toFloat(), y, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_settings_card, parent, false)
        view.layoutParams = ViewGroup.LayoutParams(CARD_SIZE, CARD_SIZE)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundColor(
                if (hasFocus) ContextCompat.getColor(parent.context, R.color.selected_background)
                else ContextCompat.getColor(parent.context, R.color.default_background)
            )
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val card = item as Item
        val iconView = viewHolder.view.findViewById<ImageView>(R.id.settings_card_icon)
        if (card.iconChar != null) {
            iconView.setImageDrawable(TextIconDrawable(card.iconChar))
        } else {
            iconView.setImageResource(card.iconRes)
        }
        viewHolder.view.findViewById<TextView>(R.id.settings_card_text).text = card.label
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}

    companion object {
        private const val CARD_SIZE = 200
    }
}
