package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RuleRecordingIndex] のテスト。
 *
 * 見ているのは「全ルール分の応答が揃うまで待てること」「応答の到着順に結果が左右されないこと」
 * 「失敗したルールがあっても待ち合わせが止まらないこと」。ここが崩れると、一覧の並びが
 * 開くたびに変わったり、1本の失敗で全件の並べ替えが永久に終わらなくなる。
 *
 * 期待値は実装の内部ではなく [RuleOrder] が公表している規則（日時の降順、同時刻は
 * EPGStation が返した順、録画なしと失敗は末尾）から書いている。
 */
class RuleRecordingIndexTest {

    private data class Recording(val startAt: Long)

    private fun index(ruleIds: List<Long>): RuleRecordingIndex<Recording> =
        RuleRecordingIndex(ruleIds) { recording: Recording -> recording.startAt }

    /** 指定の順に「録画あり」の応答を流し、各 report の戻り値を返す。 */
    private fun RuleRecordingIndex<Recording>.reportAll(
        order: List<Long>,
        recordedAt: Map<Long, Long>
    ): List<Boolean> = order.map { ruleId ->
        report(ruleId, listOf(Recording(recordedAt.getValue(ruleId))), 1)
    }

    // --- 完了の判定 ---

    @Test
    fun 全ルールが揃った最初の報告だけがtrueを返す() {
        val idx = index(listOf(1L, 2L, 3L))

        assertFalse(idx.isComplete)
        val results = idx.reportAll(listOf(1L, 2L, 3L), mapOf(1L to 100L, 2L to 200L, 3L to 300L))

        assertEquals(listOf(false, false, true), results)
        assertTrue(idx.isComplete)
        assertEquals(3, idx.completedCount)

        // 完了後も報告は受け付けるが true にはならない
        assertFalse(idx.report(1L, listOf(Recording(1)), 1))
        assertFalse(idx.report(4L, listOf(Recording(4)), 1))
    }

    @Test
    fun 同じIDを二度報告しても数えない() {
        val idx = index(listOf(1L, 2L))

        assertFalse(idx.report(1L, listOf(Recording(10)), 1))
        // 二度目は数えないので、まだ 1 件ぶんしか完了していない
        assertFalse(idx.report(1L, listOf(Recording(20)), 5))
        assertEquals(1, idx.completedCount)
        assertFalse(idx.isComplete)

        // 2件目の初回で完了する。以降は同じ ID でも知らない ID でも true にはならない
        assertTrue(idx.report(2L, listOf(Recording(30)), 1))
        assertTrue(idx.isComplete)
        assertFalse(idx.report(1L, listOf(Recording(40)), 1))
        assertFalse(idx.report(3L, listOf(Recording(50)), 1))
        assertEquals(2, idx.completedCount)
    }

    @Test
    fun 知らないIDの応答は捨てる() {
        val idx = index(listOf(1L, 2L))

        assertFalse(idx.report(99L, listOf(Recording(1)), 1))
        assertFalse(idx.report(0L, null, null))

        assertEquals(0, idx.completedCount)
        assertEquals(0, idx.failedCount)
        assertFalse(idx.isComplete)
        assertNull(idx.snapshot(99L))
    }

    @Test
    fun ルールが0件なら最初から完了している() {
        val idx = index(emptyList())

        assertTrue(idx.isComplete)
        assertEquals(0, idx.completedCount)
        assertEquals(0, idx.failedCount)
        assertFalse(idx.report(1L, listOf(Recording(1)), 1))
        assertEquals(emptyList<Long>(), idx.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
        assertEquals(emptyList<Long>(), idx.orderedRuleIds(RuleOrder.MODE_RULE_NEWEST))
    }

    // --- 失敗の扱い ---

    @Test
    fun recordsやtotalの欠落とtotal負は失敗として数え完了を進める() {
        val idx = index(listOf(1L, 2L, 3L, 4L))

        assertFalse(idx.report(1L, listOf(Recording(100)), null))
        assertFalse(idx.report(2L, null, 1))
        assertFalse(idx.report(3L, listOf(Recording(300)), -1))
        // 1本失敗しても待ち合わせは止まらず、最後の1件で完了する
        assertTrue(idx.report(4L, listOf(Recording(400)), 1))

        assertTrue(idx.isComplete)
        assertEquals(4, idx.completedCount)
        assertEquals(3, idx.failedCount)

        // 失敗したルールの控えは残さない
        assertNull(idx.snapshot(1L))
        assertNull(idx.snapshot(2L))
        assertNull(idx.snapshot(3L))
        assertEquals(1, idx.snapshot(4L)?.total)

        // 録画がないルールと同じ扱いで末尾へ回る（成功した 4L だけが先頭）
        assertEquals(listOf(4L, 1L, 2L, 3L), idx.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
    }

    // --- 控え ---

    @Test
    fun 控えは成功した応答だけが残る() {
        val idx = index(listOf(7L, 8L))

        assertNull(idx.snapshot(7L))

        idx.report(7L, listOf(Recording(42)), 9)

        assertEquals(9, idx.snapshot(7L)?.total)
        assertEquals(listOf(42L), idx.snapshot(7L)?.records?.map { it.startAt })
        assertNull(idx.snapshot(8L))
    }

    // --- 並べ替え ---

    @Test
    fun 応答が逆順に届いても並びは同じ() {
        val ids = listOf(1L, 2L, 3L, 4L)
        val recordedAt = mapOf(1L to 1000L, 2L to 4000L, 3L to 3000L, 4L to 2000L)

        val forward = index(ids)
        val backward = index(ids)
        assertEquals(1, forward.reportAll(ids, recordedAt).count { it })
        assertEquals(1, backward.reportAll(ids.asReversed(), recordedAt).count { it })

        val expected = listOf(2L, 3L, 4L, 1L)
        assertEquals(expected, forward.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
        assertEquals(expected, backward.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
        assertTrue(forward.isComplete)
        assertTrue(backward.isComplete)
    }

    @Test
    fun 複数件の録画が返っても最大のstartAtを使う() {
        val idx = index(listOf(1L, 2L, 3L))

        // limit=1 の想定でも、先頭の1件が最新とは限らないので最大値で判定する
        idx.report(1L, listOf(Recording(1000), Recording(9000), Recording(5000)), 3)
        idx.report(2L, listOf(Recording(7000)), 2)
        idx.report(3L, listOf(Recording(8000)), 1)

        assertEquals(listOf(1L, 3L, 2L), idx.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
    }

    @Test
    fun 録画がないルールは末尾に回る() {
        val idx = index(listOf(1L, 2L, 3L))

        idx.report(1L, listOf(Recording(1000)), 1)
        idx.report(2L, emptyList(), 0)
        idx.report(3L, listOf(Recording(2000)), 1)

        assertTrue(idx.isComplete)
        assertEquals(0, idx.failedCount)
        assertEquals(0, idx.snapshot(2L)?.total)
        assertEquals(listOf(3L, 1L, 2L), idx.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
    }

    @Test
    fun startAtが同じときはEPGStationが返した順を保つ() {
        val idx = index(listOf(1L, 2L, 3L))

        idx.report(1L, listOf(Recording(5000)), 1)
        idx.report(2L, listOf(Recording(5000)), 1)
        idx.report(3L, listOf(Recording(5000)), 1)

        assertEquals(listOf(1L, 2L, 3L), idx.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
    }

    @Test
    fun モードごとの並びはRuleOrderに従う() {
        val idx = index(listOf(1L, 2L, 3L))

        idx.report(1L, listOf(Recording(1000)), 1)
        idx.report(2L, listOf(Recording(3000)), 1)
        idx.report(3L, listOf(Recording(2000)), 1)

        assertEquals(listOf(3L, 2L, 1L), idx.orderedRuleIds(RuleOrder.MODE_RULE_NEWEST))
        assertEquals(listOf(1L, 2L, 3L), idx.orderedRuleIds(RuleOrder.MODE_RULE_OLDEST))
        assertEquals(listOf(2L, 3L, 1L), idx.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST))
    }

    // --- 現実に近い件数 ---

    @Test
    fun 逆順に報告した600件でも並びと完了判定は正しい() {
        val ids = (1L..600L).toList()
        val idx = index(ids)

        // 奇数番だけ録画あり、という現実に近い入力を、EPGStation の応答とは逆の順で流す
        val results = ids.asReversed().map { ruleId ->
            if (ruleId % 2L == 1L) {
                idx.report(ruleId, listOf(Recording(ruleId * 100L)), 1)
            } else {
                idx.report(ruleId, emptyList(), 0)
            }
        }

        assertTrue(idx.isComplete)
        assertEquals(600, idx.completedCount)
        assertEquals(0, idx.failedCount)
        // 600回のうち true は最後の1回だけ
        assertEquals(1, results.count { it })
        assertTrue(results.last())

        val order = idx.orderedRuleIds(RuleOrder.MODE_RECORDING_NEWEST)
        assertEquals(600, order.size)
        assertEquals(ids.toSet(), order.toSet())
        // 録画のあるルールは startAt の降順（ruleId が大きいほど新しい）
        val withRecording = order.filter { it % 2L == 1L }
        assertEquals(withRecording.sortedDescending(), withRecording)
        // 録画のないルールは必ず末尾へ回る
        assertTrue(order.indexOfLast { it % 2L == 1L } < order.indexOfFirst { it % 2L == 0L })
    }
}
