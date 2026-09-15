package com.daigorian.epcltvapp

import java.util.HashMap

/**
 * 録画ルール一覧の並び順（「最後に録画された番組が新しい順」）を決めるロジック。
 *
 * ルールごとの最終録画日時は、一覧の各行を作るために元から走っている
 * `getRecorded(ruleId = ...)` の応答から取り出す。並べ替えのためだけの通信は行わない。
 *
 * Android に依存しないので、単体テストから直接呼べる。
 *
 * ## 並び順の規則
 *
 * 優先度の高い順に次で決める。上の条件で差が付かないときだけ次を使う。
 *
 * | | 条件 |
 * |---|---|
 * | 1 | 最終録画日時（録画済み番組の startAt）が新しい順 |
 * | 2 | 最終録画日時が同じルールは、EPGStation が返したルール順（`provisionalOrder`） |
 *
 * 録画実績がないルール、および最終録画日時を取得できなかったルールは、
 * 昇順・降順のどちらで並べても**必ず末尾**へ回す。末尾に回ったルール同士は
 * `provisionalOrder` の順を保つ。
 */
object RuleOrder {

    /** 録画実績がないルールに与える日時。降順ソートなので必ず末尾へ回る。 */
    private const val NO_RECORDING = Long.MIN_VALUE

    /**
     * @param provisionalOrder EPGStation が返したルール ID の並び。表示設定で逆順にした後のものを渡す。
     *        規則2と、録画実績なしのルール同士の並びを安定させるために使う。
     * @param latestRecordedAt ruleId → そのルールで最後に録画された番組の startAt (ms)。
     *        録画実績がないルールと取得に失敗したルールは含めない。
     * @return 並べ替えた ruleId のリスト。`provisionalOrder` と同じ要素を過不足なく含む。
     */
    fun sortByLatestRecorded(
        provisionalOrder: List<Long>,
        latestRecordedAt: Map<Long, Long>
    ): List<Long> {
        // ルールは数百件になるため、毎回 indexOf で線形探索せず位置を引けるようにしておく
        val provisionalIndex = HashMap<Long, Int>(provisionalOrder.size * 2)
        provisionalOrder.forEachIndexed { index, ruleId -> provisionalIndex[ruleId] = index }

        return provisionalOrder.sortedWith(
            compareByDescending<Long> { latestRecordedAt[it] ?: NO_RECORDING }
                .thenBy { provisionalIndex[it] ?: Int.MAX_VALUE }
        )
    }
}
