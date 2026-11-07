package com.daigorian.epcltvapp

import com.daigorian.epcltvapp.epgstationv2caller.ChannelItem
import com.daigorian.epcltvapp.epgstationv2caller.Schedule
import com.daigorian.epcltvapp.epgstationv2caller.ScheduleChannelItem
import com.daigorian.epcltvapp.epgstationv2caller.ScheduleProgramItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    private fun program(channelId: Long, start: Long, end: Long, isFree: Boolean) =
        ScheduleProgramItem(channelId = channelId, startAt = start, endAt = end, isFree = isFree, name = "P$start")

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

    @Test fun paidChannelIsRemovedAndOthersKeepOrder() {
        val free = sat("BS", 141, "BS13_0")
        val paid = sat("BS", 191, "BS03_0")
        val cs = sat("CS", 218, "CS08", networkId = 6)
        val channels = listOf(free, paid, cs)
        val onAir = channels.map { it.id }.toSet()
        assertEquals(listOf(free, cs), LiveLineup.visible(channels, emptySet(), onAir, setOf(paid.id)))
    }

    @Test fun paidSubChannelWithProgramIsRemoved() {
        val main = gr(1024)
        val sub = gr(1025)
        assertEquals(listOf(main), LiveLineup.visible(listOf(main, sub), emptySet(), setOf(main.id, sub.id), setOf(sub.id)))
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

    @Test fun skipsPaidChannel() {
        val first = sat("BS", 141, "BS13_0")
        val paid = sat("BS", 191, "BS03_0")
        val last = sat("BS", 211, "BS09_2")
        val channels = listOf(first, paid, last)
        val onAir = channels.map { it.id }.toSet()
        assertSame(last, LiveLineup.neighbor(channels, emptySet(), onAir, first.id, forward = true, paidIds = setOf(paid.id)))
        assertSame(first, LiveLineup.neighbor(channels, emptySet(), onAir, last.id, forward = false, paidIds = setOf(paid.id)))
    }

    @Test fun fromChannelThatTurnedPaidMovesByPosition() {
        val first = sat("BS", 141, "BS13_0")
        val paid = sat("BS", 191, "BS03_0")
        val last = sat("BS", 211, "BS09_2")
        val channels = listOf(first, paid, last)
        val onAir = channels.map { it.id }.toSet()
        assertSame(last, LiveLineup.neighbor(channels, emptySet(), onAir, paid.id, forward = true, paidIds = setOf(paid.id)))
        assertSame(first, LiveLineup.neighbor(channels, emptySet(), onAir, paid.id, forward = false, paidIds = setOf(paid.id)))
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

    // --- 有料放送中の局 ---

    @Test fun paidIdsAreChannelsWhoseCurrentProgramIsPaid() {
        val schedules = listOf(
            Schedule(ScheduleChannelItem(1), listOf(program(1, 0, 100, isFree = true))),
            Schedule(ScheduleChannelItem(2), listOf(program(2, 0, 100, isFree = false))),
            // 有料の局で、いまは無料放送
            Schedule(ScheduleChannelItem(3), listOf(program(3, 0, 60, isFree = false), program(3, 60, 100, isFree = true))),
            // いまは番組が無い。有料か分からないので外さない
            Schedule(ScheduleChannelItem(4), listOf(program(4, 0, 40, isFree = false), program(4, 80, 100, isFree = false)))
        )
        assertEquals(setOf(2L), LiveLineup.paidIds(LiveLineup.currentPrograms(schedules, 70)))
    }

    @Test fun paidChannelReturnsWhenFreeProgramStarts() {
        val schedules = listOf(
            Schedule(ScheduleChannelItem(1), listOf(program(1, 0, 100, isFree = false), program(1, 100, 200, isFree = true)))
        )
        assertEquals(setOf(1L), LiveLineup.paidIds(LiveLineup.currentPrograms(schedules, 99)))
        assertEquals(emptySet<Long>(), LiveLineup.paidIds(LiveLineup.currentPrograms(schedules, 100)))
    }

    @Test fun programWithoutIsFreeIsTreatedAsFree() {
        // 項目が無いサーバーで、すべての局を有料扱いにして消さないこと
        val json = """[{"channel":{"id":1},"programs":[{"id":10,"channelId":1,"startAt":0,"endAt":100,"name":"P"}]}]"""
        val schedules = Gson().fromJson<List<Schedule>>(json, object : TypeToken<List<Schedule>>() {}.type)
        assertTrue(schedules[0].programs[0].isFree)
        assertEquals(emptySet<Long>(), LiveLineup.paidIds(LiveLineup.currentPrograms(schedules, 50)))
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
