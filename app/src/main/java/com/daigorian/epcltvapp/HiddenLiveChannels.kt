package com.daigorian.epcltvapp

import android.content.Context
import androidx.preference.PreferenceManager
import com.daigorian.epcltvapp.epgstationv2caller.ChannelItem

object HiddenLiveChannels {
    const val KEY = "KEY_HIDDEN_LIVE_CHANNELS"
    private const val NAME_PREFIX = "hidden_live_channel_name_"

    fun ids(context: Context): Set<String> =
        PreferenceManager.getDefaultSharedPreferences(context).getStringSet(KEY, emptySet())
            .orEmpty().toSet()

    fun hide(context: Context, channel: ChannelItem) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(NAME_PREFIX + channel.id, channel.halfWidthName.ifEmpty { channel.name })
            .putStringSet(KEY, ids(context) + channel.id.toString())
            .apply()
    }

    fun name(context: Context, id: String): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(NAME_PREFIX + id, null)
            ?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.live_channel_fallback_name, id)

    fun update(context: Context, hiddenIds: Set<String>) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        (ids(context) - hiddenIds).forEach { editor.remove(NAME_PREFIX + it) }
        editor.putStringSet(KEY, hiddenIds.toSet()).apply()
    }

    fun visible(channels: List<ChannelItem>, hiddenIds: Set<String>): List<ChannelItem> =
        channels.filter { ChannelItem.isAudioVideoService(it.type) && it.id.toString() !in hiddenIds }

    fun restoredSelectionIndex(
        channels: List<ChannelItem>,
        selectedId: Long?,
        previousIndex: Int
    ): Int {
        if (channels.isEmpty()) return -1
        val sameChannel = channels.indexOfFirst { it.id == selectedId }
        return if (sameChannel >= 0) sameChannel else previousIndex.coerceIn(0, channels.lastIndex)
    }
}
