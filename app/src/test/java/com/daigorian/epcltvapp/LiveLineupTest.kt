package com.daigorian.epcltvapp

import com.daigorian.epcltvapp.epgstationv2caller.ChannelItem
import com.daigorian.epcltvapp.epgstationv2caller.Schedule
import com.daigorian.epcltvapp.epgstationv2caller.ScheduleChannelItem
import com.daigorian.epcltvapp.epgstationv2caller.ScheduleProgramItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LiveLineupTest {
    private fun gr(serviceId: Long, networkId: Long = 32736, channel: String? = "T27", type: Int = 1) =
        ChannelItem(id = networkId * 100000 + serviceId, name = "GR$serviceId", type = type,
            serviceId = serviceId, networkId = networkId, channelType = "GR", channel = channel)

    private fun sat(channelType: String, serviceId: Long, channel: String, networkId: Long = 4) =
        ChannelItem(id = networkId * 100000 + serviceId, name = "$channelType$serviceId", type = 1,
            serviceId = serviceId, networkId = networkId, channelType = channelType, channel = channel)

    private fun schedule(channelId: Long, vararg programs: Pair<Long, Long>) =
        Schedule(ScheduleChannelItem(channelId), programs.map { (start, end) ->
            ScheduleProgramItem(channelId = channelId, startAt = start, endAt = end, name = "P$start")
        })

    // --- サブチャンネルの判定 ---

    @Test fun lowestServiceIdInSameTsIsMain() {
        val main = gr(1024)
        val sub = gr(1025)
        assertEquals(setOf(sub.id), LiveLineup.subChannelIds(listOf(sub, main)))
    }

    @Test fun dataServiceNeitherBecomesSubNorTakesMain() {
        val data = gr(1000, type = 0xc0)
        val main = gr(1024)
        val sub = gr(1025)
        assertEquals(setOf(sub.id), LiveLineup.subChannelIds(listOf(data, main, sub)))
    }

    @Test fun eachBsTsHasItsOwnMain() {
        val channels = listOf(
            sat("BS", 151, "BS01_0"), sat("BS", 152, "BS01_0"), sat("BS", 153, "BS01_0"),
            sat("BS", 191, "BS03_0"), sat("BS", 192, "BS05_0")
        )
        assertEquals(setOf(channels[1].id, channels[2].id), LiveLineup.subChannelIds(channels))
    }

    @Test fun csAndSkyHaveNoSubChannels() {
        val channels = listOf(
            sat("CS", 55, "CS08", networkId = 6), sat("CS", 218, "CS08", networkId = 6),
            sat("SKY", 100, "SKY1", networkId = 1), sat("SKY", 200, "SKY1", networkId = 1)
        )
        assertEquals(emptySet<Long>(), LiveLineup.subChannelIds(channels))
    }

    @Test fun channelWithoutTsIsTreatedAsMain() {
        assertEquals(emptySet<Long>(), LiveLineup.subChannelIds(listOf(gr(1024, channel = null), gr(1025, channel = null))))
    }

    @Test fun sameChannelNameOnDifferentNetworksIsDifferentTs() {
        val channels = listOf(gr(1024, networkId = 32736), gr(1025, networkId = 32737))
        assertEquals(emptySet<Long>(), LiveLineup.subChannelIds(channels))
    }

    // --- 並べる局 ---

    @Test fun idleSubChannelIsHiddenButIdleMainStays() {
        val main = gr(1024)
        val idleSub = gr(1025)
        assertEquals(listOf(main), LiveLineup.visible(listOf(main, idleSub), emptySet(), emptySet()))
    }

    @Test fun subChannelWithProgramIsShownInOrder() {
        val main = gr(1024)
        val sub = gr(1025)
        val next = gr(1032, networkId = 32737, channel = "T26")
        val visible = LiveLineup.visible(listOf(main, sub, next), emptySet(), setOf(sub.id))
        assertEquals(listOf(main, sub, next), visible)
        assertSame(sub, visible[1])
    }

    @Test fun userHiddenSubChannelStaysHiddenEvenWithProgram() {
        val main = gr(1024)
        val sub = gr(1025)
        assertEquals(listOf(main), LiveLineup.visible(listOf(main, sub), setOf(sub.id.toString()), setOf(sub.id)))
    }

    @Test fun subChannelIsJudgedFromAllChannelsEvenIfMainIsUserHidden() {
        val main = gr(1024)
        val idleSub = gr(1025)
        assertEquals(emptyList<ChannelItem>(), LiveLineup.visible(listOf(main, idleSub), setOf(main.id.toString()), emptySet()))
    }

    // --- 左右キーで移る先 ---

    @Test fun rightAndLeftMoveToAdjacentChannels() {
        val channels = listOf(gr(1024), gr(1032, networkId = 32737), gr(1040, networkId = 32738))
        assertSame(channels[2], LiveLineup.neighbor(channels, emptySet(), emptySet(), channels[1].id, forward = true))
        assertSame(channels[0], LiveLineup.neighbor(channels, emptySet(), emptySet(), channels[1].id, forward = false))
    }

    @Test fun endsWrapAround() {
        val channels = listOf(gr(1024), gr(1032, networkId = 32737), gr(1040, networkId = 32738))
        assertSame(channels[0], LiveLineup.neighbor(channels, emptySet(), emptySet(), channels[2].id, forward = true))
        assertSame(channels[2], LiveLineup.neighbor(channels, emptySet(), emptySet(), channels[0].id, forward = false))
    }

    @Test fun skipsHiddenChannelAndIdleSubChannel() {
        val main = gr(1024)
        val idleSub = gr(1025)
        val hidden = gr(1032, networkId = 32737)
        val next = gr(1040, networkId = 32738)
        val channels = listOf(main, idleSub, hidden, next)
        assertSame(next, LiveLineup.neighbor(channels, setOf(hidden.id.toString()), emptySet(), main.id, forward = true))
        assertSame(main, LiveLineup.neighbor(channels, setOf(hidden.id.toString()), emptySet(), next.id, forward = false))
    }

    @Test fun subChannelWithProgramIsNotSkipped() {
        val main = gr(1024)
        val sub = gr(1025)
        val next = gr(1032, networkId = 32737)
        assertSame(sub, LiveLineup.neighbor(listOf(main, sub, next), emptySet(), setOf(sub.id), main.id, forward = true))
    }

    @Test fun channelNotInLineupMovesByPositionAmongAllChannels() {
        val first = gr(1024)
        val hidden = gr(1032, networkId = 32737)
        val last = gr(1040, networkId = 32738)
        val channels = listOf(first, hidden, last)
        val hiddenIds = setOf(hidden.id.toString())
        assertSame(last, LiveLineup.neighbor(channels, hiddenIds, emptySet(), hidden.id, forward = true))
        assertSame(first, LiveLineup.neighbor(channels, hiddenIds, emptySet(), hidden.id, forward = false))
    }

    @Test fun noOtherChannelIsNull() {
        val only = gr(1024)
        val idleSub = gr(1025)
        assertNull(LiveLineup.neighbor(listOf(only, idleSub), emptySet(), emptySet(), only.id, forward = true))
    }

    @Test fun unknownCurrentChannelIsNull() {
        assertNull(LiveLineup.neighbor(listOf(gr(1024)), emptySet(), emptySet(), 1L, forward = true))
    }

    // --- いまの番組 ---

    @Test fun currentProgramContainsNow() {
        val schedules = listOf(schedule(1, 0L to 100L, 100L to 200L))
        assertEquals(100L, LiveLineup.currentPrograms(schedules, 150)[1]?.startAt)
    }

    @Test fun programStartingNowIsCurrentAndEndingNowIsNot() {
        val schedules = listOf(schedule(1, 0L to 100L, 100L to 200L))
        assertEquals(100L, LiveLineup.currentPrograms(schedules, 100)[1]?.startAt)
    }

    @Test fun channelInGapHasNoCurrentProgram() {
        val schedules = listOf(schedule(1, 0L to 100L, 300L to 400L))
        assertNull(LiveLineup.currentPrograms(schedules, 200)[1])
    }

    // --- 次に変わる時刻 ---

    @Test fun nextChangeIsEndOfCurrentProgram() {
        val schedules = listOf(schedule(1, 0L to 100L, 100L to 200L))
        assertEquals(100L, LiveLineup.nextChangeAt(schedules, setOf(1L), 50))
    }

    @Test fun nextChangeInGapIsStartOfNextProgram() {
        val schedules = listOf(schedule(1, 0L to 100L, 300L to 400L))
        assertEquals(300L, LiveLineup.nextChangeAt(schedules, setOf(1L), 200))
    }

    @Test fun nextChangeIsEarliestAmongCandidates() {
        val schedules = listOf(schedule(1, 0L to 500L), schedule(2, 250L to 300L), schedule(3, 0L to 120L))
        assertEquals(250L, LiveLineup.nextChangeAt(schedules, setOf(1L, 2L), 100))
    }

    @Test fun changeAtNowIsNotNext() {
        val schedules = listOf(schedule(1, 0L to 100L, 100L to 200L))
        assertEquals(200L, LiveLineup.nextChangeAt(schedules, setOf(1L), 100))
    }

    @Test fun noChangeInRangeIsNull() {
        val schedules = listOf(schedule(1, 0L to 100L))
        assertNull(LiveLineup.nextChangeAt(schedules, setOf(1L), 150))
        assertNull(LiveLineup.nextChangeAt(emptyList(), setOf(1L), 150))
    }
}
