package com.daigorian.epcltvapp

import com.daigorian.epcltvapp.epgstationv2caller.ChannelItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HiddenLiveChannelsTest {
    private val channels = listOf(ChannelItem(1, "One"), ChannelItem(2, "Two"))

    @Test fun noHiddenChannelsPreservesOrderAndInstances() {
        val visible = HiddenLiveChannels.visible(channels, emptySet())
        assertEquals(channels, visible)
        assertSame(channels[0], visible[0])
    }

    @Test fun hidesOnlySelectedId() {
        assertEquals(listOf(channels[1]), HiddenLiveChannels.visible(channels, setOf("1")))
    }

    @Test fun canHideAllChannels() {
        assertEquals(emptyList<ChannelItem>(), HiddenLiveChannels.visible(channels, setOf("1", "2")))
    }

    @Test fun unknownIdsHaveNoEffect() {
        assertEquals(channels, HiddenLiveChannels.visible(channels, setOf("999")))
    }

    @Test fun dataServicesRemainExcluded() {
        assertEquals(
            channels,
            HiddenLiveChannels.visible(channels + ChannelItem(3, "Data", type = 0xc0), emptySet())
        )
    }

    @Test fun sameNameDoesNotHideOtherId() {
        val sameName = listOf(ChannelItem(1, "Same"), ChannelItem(2, "Same"))
        assertEquals(listOf(sameName[1]), HiddenLiveChannels.visible(sameName, setOf("1")))
    }

    @Test fun visibleSelectedChannelKeepsItsIndex() {
        assertEquals(1, HiddenLiveChannels.restoredSelectionIndex(channels, 2, 0))
    }

    @Test fun hiddenSelectionUsesCardAtPreviousIndex() {
        assertEquals(0, HiddenLiveChannels.restoredSelectionIndex(channels, 1, 0))
    }

    @Test fun hiddenLastChannelMovesToNewLastCard() {
        val threeChannels = channels + ChannelItem(3, "Three")
        val visible = HiddenLiveChannels.visible(threeChannels, setOf("3"))
        assertEquals(1, HiddenLiveChannels.restoredSelectionIndex(visible, 3, 2))
    }

    @Test fun noVisibleChannelsHasNoCardSelection() {
        assertEquals(-1, HiddenLiveChannels.restoredSelectionIndex(emptyList(), 1, 0))
    }
}
