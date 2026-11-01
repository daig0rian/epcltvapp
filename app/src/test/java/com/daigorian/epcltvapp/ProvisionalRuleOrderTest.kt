package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ProvisionalRuleOrderTest {
    @Test fun partialResponsesKeepDescendingIdsUntilLastResponse() {
        val index = RuleRecordingIndex<Long>(listOf(1, 2, 3)) { it }
        assertEquals(listOf(3L, 2L, 1L), index.displayOrder(RuleOrder.MODE_RECORDING_NEWEST))
        index.report(1, listOf(300), 1)
        index.report(3, listOf(100), 1)
        assertEquals(listOf(3L, 2L, 1L), index.displayOrder(RuleOrder.MODE_RECORDING_NEWEST))
        index.report(2, listOf(200), 1)
        assertEquals(listOf(1L, 2L, 3L), index.displayOrder(RuleOrder.MODE_RECORDING_NEWEST))
    }

    @Test fun ruleIdModesDoNotWaitForRecordingResponses() {
        val index = RuleRecordingIndex<Long>(listOf(1, 2, 3)) { it }
        assertEquals(listOf(3L, 2L, 1L), index.displayOrder(RuleOrder.MODE_RULE_NEWEST))
        assertEquals(listOf(1L, 2L, 3L), index.displayOrder(RuleOrder.MODE_RULE_OLDEST))
    }
}
