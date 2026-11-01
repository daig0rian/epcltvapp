package com.daigorian.epcltvapp

import org.junit.Assert.*
import org.junit.Test

class RecordedPageProgressTest {
    @Test fun shortPageAdvancesByReturnedRecordsNotRequestedLimit() {
        assertEquals(2L, RecordedPageProgress.nextOffset(0, 2))
        assertEquals(5L, RecordedPageProgress.nextOffset(2, 3))
        assertEquals(10L, RecordedPageProgress.requestLimit(2, 12))
        assertEquals(4L, RecordedPageProgress.requestLimit(8, 12))
        assertEquals(12L, RecordedPageProgress.requestLimit(12, 12))
    }

    @Test fun emptyPageBeforeTotalIsAnErrorRatherThanACompletedWindow() {
        assertEquals(2L, RecordedPageProgress.nextOffset(2, 0))
        assertTrue(RecordedPageProgress.isUnexpectedEmpty(2, 0, 5))
        assertFalse(RecordedPageProgress.isUnexpectedEmpty(5, 0, 5))
        assertFalse(RecordedPageProgress.isUnexpectedEmpty(0, 0, 0))
    }

    @Test fun indexDemandCanPauseAndResumeWithoutLosingCollectedDates() {
        val index = RuleRecordingIndex<Long>(listOf(1, 2)) { it }
        index.seed(1, listOf(100))
        assertTrue(index.needsLookup(RuleOrder.MODE_RECORDING_NEWEST, true))
        assertFalse(index.needsLookup(RuleOrder.MODE_RULE_NEWEST, true))
        assertTrue(index.needsLookup(RuleOrder.MODE_RULE_NEWEST, false))
        assertFalse(index.needsLookup(RuleOrder.MODE_RULE_OLDEST, true))
        assertTrue(index.needsLookup(RuleOrder.MODE_RECORDING_NEWEST, true))
        assertEquals(1, index.completedCount)
        index.report(2, emptyList(), 0)
        assertFalse(index.needsLookup(RuleOrder.MODE_RECORDING_NEWEST, false))
    }
}
