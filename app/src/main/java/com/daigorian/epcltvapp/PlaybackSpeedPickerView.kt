package com.daigorian.epcltvapp

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 再生速度を上下キーで選ぶための一覧。再生コントロールの速度ボタンの上に重ねて出す。
 *
 * **このビュー自身はフォーカスを取らない。** フォーカスは速度ボタンに置いたままにして、
 * 上下キーは [PlaybackVideoFragment] が横取りしてここのカーソルを動かす。フォーカスを
 * 渡してしまうと、Leanback のフォーカス管理(コントロール行の中の移動・行の選択位置)と
 * 二重になり、閉じたあとにどこへ戻るのかが定まらなくなるため。
 *
 * そのため行の見た目は state_focused ではなく state_selected で切り替える
 * (bg_speed_picker_item.xml / color/speed_picker_item_text.xml)。
 */
class PlaybackSpeedPickerView(context: Context) : LinearLayout(context) {

    private val itemViews = mutableListOf<TextView>()

    /** カーソルのある行。 */
    var selectedIndex: Int = 0
        set(value) {
            field = value
            itemViews.forEachIndexed { index, item -> item.isSelected = index == value }
        }

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_resume_dialog)
        val padding = dp(PADDING_DP)
        setPadding(padding, padding, padding, padding)
        // フォーカスはボタン側に置いたままにする(上のKDoc参照)。
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
    }

    /** 選択肢を作り直す。カーソルは先頭に戻るので、必要なら [selectedIndex] を入れ直すこと。 */
    fun setEntries(labels: List<String>) {
        removeAllViews()
        itemViews.clear()
        val paddingH = dp(ITEM_PADDING_H_DP)
        val paddingV = dp(ITEM_PADDING_V_DP)
        for (label in labels) {
            val item = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ITEM_TEXT_SP)
                setTextColor(ContextCompat.getColorStateList(context, R.color.speed_picker_item_text))
                setBackgroundResource(R.drawable.bg_speed_picker_item)
                setPadding(paddingH, paddingV, paddingH, paddingV)
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            addView(item)
            itemViews.add(item)
        }
        selectedIndex = 0
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PADDING_DP = 8f
        private const val ITEM_PADDING_H_DP = 16f
        private const val ITEM_PADDING_V_DP = 6f
        // ボタンのキャプション(ACTION_CAPTION_TEXT_SP)と同じ大きさに揃える。
        private const val ITEM_TEXT_SP = 14f
    }
}
