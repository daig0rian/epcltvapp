package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RuleOrder] のテスト。
 *
 * 見ているのは「最近録画されたルールが先に来ること」と「並びが毎回同じになること」。
 * 後者を落とすと、アプリを開き直すたびに同じルールが別の位置に現れ、利用者から見て
 * 一覧が勝手に動いているように映る。
 */
class RuleOrderTest {

    @Test
    fun 最後に録画された番組が新しいルールほど先に来る() {
        val order = RuleOrder.sortByLatestRecorded(
            provisionalOrder = listOf(1L, 2L, 3L),
            latestRecordedAt = mapOf(
                1L to 1000L,
                2L to 3000L,
                3L to 2000L
            )
        )
        assertEquals(listOf(2L, 3L, 1L), order)
    }

    @Test
    fun 録画実績がないルールは末尾に回る() {
        val order = RuleOrder.sortByLatestRecorded(
            provisionalOrder = listOf(1L, 2L, 3L, 4L),
            latestRecordedAt = mapOf(
                1L to 1000L,
                3L to 2000L
            )
        )
        assertEquals(listOf(3L, 1L, 2L, 4L), order)
    }

    @Test
    fun 録画実績がないルール同士は元の並びを保つ() {
        val order = RuleOrder.sortByLatestRecorded(
            provisionalOrder = listOf(9L, 7L, 5L, 3L),
            latestRecordedAt = emptyMap()
        )
        assertEquals(listOf(9L, 7L, 5L, 3L), order)
    }

    @Test
    fun 同じ日時のルールは元の並びを保つ() {
        val order = RuleOrder.sortByLatestRecorded(
            provisionalOrder = listOf(1L, 2L, 3L, 4L),
            latestRecordedAt = mapOf(
                1L to 5000L,
                2L to 5000L,
                3L to 5000L,
                4L to 4000L
            )
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), order)
    }

    @Test
    fun 取得に失敗したルールは取得できたルールより後ろに置く() {
        // latestRecordedAt に入っていない = 録画実績なし or 取得失敗。どちらも同じ扱いにする。
        val order = RuleOrder.sortByLatestRecorded(
            provisionalOrder = listOf(1L, 2L, 3L),
            latestRecordedAt = mapOf(2L to 100L)
        )
        assertEquals(listOf(2L, 1L, 3L), order)
    }

    @Test
    fun 入力が空でも落ちない() {
        assertEquals(emptyList<Long>(), RuleOrder.sortByLatestRecorded(emptyList(), emptyMap()))
    }

    // --- 設定値の解釈 ---

    @Test
    fun 未設定と不正値は既定のルールの新しい順になる() {
        assertEquals(RuleOrder.MODE_RULE_NEWEST, RuleOrder.modeFromPreference(null))
        assertEquals(RuleOrder.MODE_RULE_NEWEST, RuleOrder.modeFromPreference(""))
        assertEquals(RuleOrder.MODE_RULE_NEWEST, RuleOrder.modeFromPreference("しらない値"))
    }

    @Test
    fun 保存された並び順はそのまま解釈する() {
        assertEquals(RuleOrder.MODE_RULE_NEWEST, RuleOrder.modeFromPreference(RuleOrder.MODE_RULE_NEWEST))
        assertEquals(RuleOrder.MODE_RULE_OLDEST, RuleOrder.modeFromPreference(RuleOrder.MODE_RULE_OLDEST))
        assertEquals(RuleOrder.MODE_RECORDING_NEWEST, RuleOrder.modeFromPreference(RuleOrder.MODE_RECORDING_NEWEST))
    }

    // --- 並び順ごとの結果 ---

    @Test
    fun ルールの新しい順はIDの降順になる() {
        // EPGStation は rule.id の昇順で返すので、新しいルールは末尾にいる
        val order = RuleOrder.orderedRuleIds(
            RuleOrder.MODE_RULE_NEWEST,
            listOf(1L, 2L, 3L),
            emptyMap()
        )
        assertEquals(listOf(3L, 2L, 1L), order)
    }

    @Test
    fun ルールの古い順はEPGStationが返した順のまま() {
        val ids = listOf(5L, 9L, 2L)
        val order = RuleOrder.orderedRuleIds(RuleOrder.MODE_RULE_OLDEST, ids, emptyMap())
        assertEquals(ids, order)
    }

    @Test
    fun 録画の新しい順では録画実績のないルールが末尾に回る() {
        val order = RuleOrder.orderedRuleIds(
            RuleOrder.MODE_RECORDING_NEWEST,
            listOf(1L, 2L, 3L),
            mapOf(2L to 500L)
        )
        assertEquals(listOf(2L, 1L, 3L), order)
    }

    @Test
    fun 要素の過不足がない() {
        val provisional = (1L..600L).toList()
        // 600件のうち奇数番だけが録画実績を持つ、という現実に近い入力を1回だけ並べ替える
        val latest = provisional.filter { it % 2L == 1L }.associateWith { it * 100L }

        val order = RuleOrder.sortByLatestRecorded(provisional, latest)

        assertEquals(provisional.size, order.size)
        assertEquals(provisional.toSet(), order.toSet())
        // 録画実績のあるルールは必ず実績なしのルールより前に来る
        val firstWithoutRecording = order.indexOfFirst { it !in latest }
        val lastWithRecording = order.indexOfLast { it in latest }
        assertTrue(lastWithRecording < firstWithoutRecording)
    }
}
