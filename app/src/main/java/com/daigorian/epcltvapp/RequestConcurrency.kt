package com.daigorian.epcltvapp

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * EPGStation v2 のサーバーへ同時に送る要求の数（接続設定の「同時接続数」）。
 *
 * サーバーの負荷を抑えたい環境では減らし、速いサーバーと端末では増やせる。
 */
object RequestConcurrency {
    const val DEFAULT: Int = 5

    /** 少ない側ほど細かく刻む。隣どうしの比がほぼそろうフィボナッチ数にしている。 */
    val CHOICES: List<Int> = listOf(1, 2, 3, 5, 8, 13)

    fun fromPreference(value: String?): Int =
        value?.trim()?.toIntOrNull()?.let(::fromValue) ?: DEFAULT

    fun fromValue(value: Int): Int =
        if (value in CHOICES) value else DEFAULT

    /** 保存されている設定値。未設定・選択肢にない値は既定値へ戻す。 */
    fun read(context: Context): Int = fromPreference(
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.pref_key_request_concurrency), null)
    )
}
