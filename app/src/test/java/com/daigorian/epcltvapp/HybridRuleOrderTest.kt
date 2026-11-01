package com.daigorian.epcltvapp

import org.junit.Assert.*
import org.junit.Test

class HybridRuleOrderTest {
    @Test fun deletingLatestUsesNextKnownRecording() {
        val index = RuleRecordingIndex<Long>(listOf(1, 2)) { it }
        index.seed(1, listOf(200, 50))
        index.report(2, listOf(100), 1)
        assertTrue(index.replaceAfterDeletion(1, listOf(50), 1))
        assertEquals(listOf(2L, 1L), index.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
    }

    @Test fun deletingOnlyKnownRecordingInvalidatesItsIndexUntilOneLookupCompletes() {
        val index = RuleRecordingIndex<Long>(listOf(1, 2)) { it }
        index.report(1, listOf(200), 2)
        index.report(2, listOf(100), 1)
        assertFalse(index.replaceAfterDeletion(1, emptyList(), 1))
        assertFalse(index.isComplete)
        assertFalse(index.isReported(1))
        assertTrue(index.report(1, listOf(50), 1))
        assertEquals(listOf(2L, 1L), index.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
    }

    @Test fun deletingLastRecordingKeepsEmptyRuleCompleteAndLast() {
        val index = RuleRecordingIndex<Long>(listOf(1, 2)) { it }
        index.seed(1, listOf(200))
        index.report(2, listOf(100), 1)
        assertTrue(index.replaceAfterDeletion(1, emptyList(), 0))
        assertTrue(index.isComplete)
        assertEquals(listOf(2L, 1L), index.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
    }

    @Test fun seedPromotesRecentRulesWithoutInventingPerRuleTotals() {
        val index = RuleRecordingIndex<Long>(listOf(1, 2, 3, 4)) { it }
        index.seed(1, listOf(300, 250))
        index.seed(3, listOf(200))
        assertEquals(listOf(1L, 3L, 4L, 2L), index.displayOrder(RuleOrder.MODE_RECORDING_NEWEST))
        assertTrue(index.isReported(1))
        assertNull(index.snapshot(1))
        assertEquals(2, index.completedCount)
        index.report(2, listOf(150), 10)
        assertEquals(listOf(1L, 3L, 4L, 2L), index.displayOrder(RuleOrder.MODE_RECORDING_NEWEST))
        index.report(4, listOf(100), 1)
        assertEquals(listOf(1L, 3L, 2L, 4L), index.displayOrder(RuleOrder.MODE_RECORDING_NEWEST))
    }

    @Test fun emptyUnknownOrRepeatedSeedsDoNotCorruptCompletion() {
        val index = RuleRecordingIndex<Long>(listOf(1, 2)) { it }
        index.seed(99, listOf(500))
        index.seed(2, emptyList())
        assertEquals(0, index.completedCount)
        index.seed(1, listOf(100, 300))
        index.seed(1, listOf(999))
        assertEquals(1, index.completedCount)
        index.report(2, null, null)
        assertTrue(index.isComplete)
        assertEquals(1, index.failedCount)
        assertEquals(listOf(1L, 2L), index.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
    }

    @Test fun seedBoundaryTiesUseStableServerOrderOnCompletion() {
        val index = RuleRecordingIndex<Long>(listOf(1, 2, 3)) { it }
        index.seed(3, listOf(100))
        index.seed(2, listOf(100))
        index.report(1, listOf(100), 1)
        assertEquals(listOf(1L, 2L, 3L), index.displayOrder(RuleOrder.MODE_RECORDING_NEWEST))
    }

    @Test fun seedDoesNotChangeExplicitRuleIdModes() {
        val index = RuleRecordingIndex<Long>(listOf(1, 2, 3)) { it }
        index.seed(1, listOf(300))
        assertEquals(listOf(3L, 2L, 1L), index.displayOrder(RuleOrder.MODE_RULE_NEWEST))
        assertEquals(listOf(1L, 2L, 3L), index.displayOrder(RuleOrder.MODE_RULE_OLDEST))
    }
}
