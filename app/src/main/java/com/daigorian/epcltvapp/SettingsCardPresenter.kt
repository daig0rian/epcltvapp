package com.daigorian.epcltvapp

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
        val action: Action
    ) {
        enum class Action {
            CONNECTION, PLAYER, DISPLAY, RELOAD
        }
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
        viewHolder.view.findViewById<ImageView>(R.id.settings_card_icon).setImageResource(card.iconRes)
        viewHolder.view.findViewById<TextView>(R.id.settings_card_text).text = card.label
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}

    companion object {
        private const val CARD_SIZE = 200
    }
}
