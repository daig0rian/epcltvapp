package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingCardContentTest {
    @Test fun shortenedContentRetainsTimingWithoutChannelOrDescription() {
        assertEquals("2026/10/17 12:00 (50 min)", RecordingCardContent.text(
            "Channel", "2026/10/17 12:00 (50 min)", "Details", true))
    }

    @Test fun fullContentKeepsUpstreamFieldOrder() {
        assertEquals("Channel\nTiming\nDetails", RecordingCardContent.text("Channel", "Timing", "Details", false))
    }

    @Test fun missingChannelAndDescriptionDoNotLeaveBlankLines() {
        assertEquals("Timing", RecordingCardContent.text("", "Timing", null, false))
        assertEquals("Timing", RecordingCardContent.text("", "Timing", "", false))
    }
}
