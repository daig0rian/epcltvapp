package com.daigorian.epcltvapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * レジューム再生用に「動画ごとの前回停止位置」を保存する。
 *
 * 保存先は既定のSharedPreferences(=設定画面が使うもの)ではなく専用ファイルにする。
 * 件数が録画数に比例して増えていくデータであり、設定値と同居させると設定のバックアップ・
 * 全消去といった操作の巻き添えになるため。
 *
 * 値は `"<位置ms>,<更新時刻ms>"` の1行。更新時刻は [MAX_ENTRIES] 超過時に古いものから
 * 捨てるためだけに持つ(位置そのものに有効期限はない)。
 */
object PlaybackPositionStore {

    private const val TAG = "PlaybackPositionStore"
    private const val PREFS_NAME = "playback_positions"

    /**
     * 保持する動画の件数上限。これを超えたら最終更新が古いものから捨てる。
     * 1件あたり数十バイトなので上限は「無制限に増え続けない」ための歯止めであり、
     * 実用上は数百件あれば足りるという想定。
     */
    private const val MAX_ENTRIES = 500

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存済みの再生位置(ms)。記録がなければnull。 */
    fun load(context: Context, key: String): Long? {
        val raw = prefs(context).getString(key, null) ?: return null
        return parsePositionMs(raw)
    }

    fun save(context: Context, key: String, positionMs: Long) {
        val prefs = prefs(context)
        val editor = prefs.edit()
        editor.putString(key, "$positionMs,${System.currentTimeMillis()}")
        val all = prefs.all
        // 新規キーのときだけ件数が増えるので、そのときだけ超過分を追い出す。
        if (!all.containsKey(key) && all.size >= MAX_ENTRIES) {
            all.entries
                .sortedBy { parseUpdatedAtMs(it.value as? String) }
                .take(all.size - MAX_ENTRIES + 1)
                .forEach { editor.remove(it.key) }
        }
        editor.apply()
        Log.d(TAG, "save: key=$key positionMs=$positionMs")
    }

    fun remove(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
        Log.d(TAG, "remove: key=$key")
    }

    private fun parsePositionMs(raw: String): Long? =
        raw.substringBefore(',').toLongOrNull()

    private fun parseUpdatedAtMs(raw: String?): Long =
        raw?.substringAfter(',', "")?.toLongOrNull() ?: 0L
}
