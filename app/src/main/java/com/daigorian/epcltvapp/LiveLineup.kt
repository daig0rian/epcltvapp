package com.daigorian.epcltvapp

import com.daigorian.epcltvapp.epgstationv2caller.ChannelItem
import com.daigorian.epcltvapp.epgstationv2caller.Schedule
import com.daigorian.epcltvapp.epgstationv2caller.ScheduleProgramItem

/**
 * ライブ視聴の行に並べる局と、各局でいま放送中の番組を決める。
 *
 * いま番組が無いサブチャンネルは並べない。メインチャンネルは番組が無くても並べる（放送休止中だと分かるように）。
 */
object LiveLineup {
    /** サブチャンネルを判定する種別。CS・スカパーは1つの TS に別々の局が同居するので、TS 内の順位でメインを決められない。 */
    private val TYPES_WITH_SUB_CHANNELS = setOf("GR", "BS")

    /**
     * サブチャンネルの id。同じ TS の映像サービスのうち、サービス ID が最小のものをメイン、残りをサブとする。
     * TS を特定できない局（channel が無い）はメイン扱い。
     */
    fun subChannelIds(channels: List<ChannelItem>): Set<Long> =
        channels
            .filter { ChannelItem.isAudioVideoService(it.type) && it.channelType in TYPES_WITH_SUB_CHANNELS && it.channel != null }
            .groupBy { Triple(it.channelType, it.networkId, it.channel) }
            .values
            .flatMap { sameTs -> sameTs.sortedBy { it.serviceId }.drop(1) }
            .map { it.id }
            .toSet()

    /**
     * 行に並べる局。映像でないサービス、利用者が隠した局、いま番組が無いサブチャンネルを除く。
     * @param onAirIds いま番組がある局の id
     */
    fun visible(channels: List<ChannelItem>, hiddenIds: Set<String>, onAirIds: Set<Long>): List<ChannelItem> {
        val idleSubChannels = subChannelIds(channels) - onAirIds
        return HiddenLiveChannels.visible(channels, hiddenIds).filter { it.id !in idleSubChannels }
    }

    /** 局 id → [now] に放送中の番組。番組が無い局は入らない。 */
    fun currentPrograms(schedules: List<Schedule>, now: Long): Map<Long, ScheduleProgramItem> =
        schedules.mapNotNull { schedule ->
            schedule.programs.firstOrNull { it.startAt <= now && now < it.endAt }?.let { schedule.channel.id to it }
        }.toMap()

    /**
     * [channelIds] のどれかで、放送中の番組が次に変わる時刻。番組の終わりだけでなく始まりも見るので、
     * 番組表に隙間（放送休止など）があっても、明けて次の番組が始まる時刻が分かる。[schedules] の範囲に無ければ null。
     */
    fun nextChangeAt(schedules: List<Schedule>, channelIds: Set<Long>, now: Long): Long? =
        schedules
            .filter { it.channel.id in channelIds }
            .flatMap { schedule -> schedule.programs.flatMap { listOf(it.startAt, it.endAt) } }
            .filter { it > now }
            .minOrNull()
}
