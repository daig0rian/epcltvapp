package com.daigorian.epcltvapp

import java.util.HashMap

/**
 * 録画ルール一覧の並び順を決めるロジック。
 *
 * ルールごとの最終録画日時は、一覧の各行を作るために元から走っている
 * `getRecorded(ruleId = ...)` の応答から取り出す。並べ替えのためだけの通信は行わない。
 *
 * Android に依存しないので、単体テストから直接呼べる。
 *
 * ## 並び順の種類
 *
 * 設定で選べるのは次の3つ。値はそのまま保存される（[MODE_RULE_NEWEST] など）。
 *
 * | | 並び | 何で決まるか |
 * |---|---|---|
 * | [MODE_RULE_NEWEST] | ルールの新しい順 | rule.id の降順（＝EPGStation が返す順の逆） |
 * | [MODE_RULE_OLDEST] | ルールの古い順 | rule.id の昇順（＝EPGStation が返す順のまま） |
 * | [MODE_RECORDING_NEWEST] | 録画の新しい順 | [sortByLatestRecorded] を参照 |
 *
 * EPGStation の `/api/rules` は `order by rule.id ASC` で返す（`src/model/db/RuleDB.ts`）。
 * そのためルール側の2つは、受け取った順をそのまま使うか逆にするだけで決まる。
 *
 * ## 「録画の新しい順」の規則
 *
 * 優先度の高い順に次で決める。上の条件で差が付かないときだけ次を使う。
 *
 * | | 条件 |
 * |---|---|
 * | 1 | 最終録画日時（録画済み番組の startAt）が新しい順 |
 * | 2 | 最終録画日時が同じルールは、EPGStation が返したルール順 |
 *
 * 録画実績がないルール、および最終録画日時を取得できなかったルールは、
 * 昇順・降順のどちらで並べても**必ず末尾**へ回す。末尾に回ったルール同士は
 * EPGStation が返した順を保つ。
 */
object RuleOrder {

    /** 設定値。ListPreference の entryValue と一字一句合わせること。 */
    const val MODE_RULE_NEWEST = "rule_newest"
    const val MODE_RULE_OLDEST = "rule_oldest"
    const val MODE_RECORDING_NEWEST = "recording_newest"

    /** 未設定・解釈できない値のときの並び。 */
    const val MODE_DEFAULT = MODE_RULE_NEWEST

    /** 録画実績がないルールに与える日時。降順ソートなので必ず末尾へ回る。 */
    private const val NO_RECORDING = Long.MIN_VALUE

    /** 設定に保存された文字列を、解釈できる並びへ正す。未設定と不正値は既定へ倒す。 */
    fun modeFromPreference(value: String?): String = when (value) {
        MODE_RULE_OLDEST, MODE_RECORDING_NEWEST -> value
        else -> MODE_DEFAULT
    }

    /**
     * 全ルール分の getRecorded が返るまでの、行を足していくときの仮の並び。
     *
     * ルールの古い順を選んでいるときだけ受け取った順（rule.id 昇順）のまま、それ以外は
     * 新しいルールを先にする。録画の新しい順は全データが揃うまで確定できないので、
     * 待っている間は「新しいルール順」で見せる。確定後の並びは [orderedRuleIds] が決める。
     */
    fun provisionalOrder(mode: String, ruleIdsInServerOrder: List<Long>): List<Long> =
        if (mode == MODE_RULE_OLDEST) ruleIdsInServerOrder else ruleIdsInServerOrder.asReversed()

    /**
     * 表示順に並べたルール ID の一覧を作る。
     *
     * @param mode [modeFromPreference] が返した並び順
     * @param ruleIdsInServerOrder EPGStation が返した順のルール ID（rule.id 昇順）
     * @param latestRecordedAt ruleId → そのルールで最後に録画された番組の startAt (ms)。
     *        録画実績がないルールと取得に失敗したルールは含めない。
     * @return 並べ替えた ruleId のリスト。`ruleIdsInServerOrder` と同じ要素を過不足なく含む。
     */
    fun orderedRuleIds(
        mode: String,
        ruleIdsInServerOrder: List<Long>,
        latestRecordedAt: Map<Long, Long>
    ): List<Long> = when (mode) {
        MODE_RULE_NEWEST -> ruleIdsInServerOrder.asReversed()
        MODE_RECORDING_NEWEST -> sortByLatestRecorded(ruleIdsInServerOrder, latestRecordedAt)
        else -> ruleIdsInServerOrder
    }

    /**
     * @param provisionalOrder EPGStation が返したルール ID の並び。
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
