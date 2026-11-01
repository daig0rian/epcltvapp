package com.daigorian.epcltvapp

/**
 * ルールごとの getRecorded 応答を集計し、[RuleOrder] で並べ替えるための控え。
 *
 * 構築時に渡した既知の ruleId だけを一度ずつ受理する。null の records/total や負の total は
 * 失敗として数えるが、失敗も完了として扱うので待ち合わせは止まらない。[report] が true を
 * 返すのは全ルールが揃った1回だけ。結果は応答の到着順に依存しない。Android 非依存。
 *
 * @param ruleIds EPGStation が返した順（rule.id 昇順）のルール ID。
 * @param startAt 録画1件から startAt (ms) を取り出す関数。
 */
class RuleRecordingIndex<T>(
    ruleIds: List<Long>,
    private val startAt: (T) -> Long
) {

    /** 1ルール分の応答の控え。 */
    data class Snapshot<T>(val records: List<T>, val total: Int)

    private val serverOrder: List<Long> = ruleIds.toList()
    private val known: Set<Long> = serverOrder.toSet()
    private val completed: MutableSet<Long> = HashSet(serverOrder.size + 1)
    private val snapshots: MutableMap<Long, Snapshot<T>> = HashMap(serverOrder.size + 1)
    private val seededLatest = HashMap<Long, Long>()
    private var failureCount: Int = 0

    /** 一度ずつ報告が終わったルールの数。失敗も含む。 */
    val completedCount: Int
        @Synchronized get() = completed.size

    /** 失敗として記録したルールの数。 */
    val failedCount: Int
        @Synchronized get() = failureCount

    /** 全ルール分の報告が揃ったか。ルールが0件なら最初から true。 */
    val isComplete: Boolean
        @Synchronized get() = completed.size == known.size

    /**
     * 1ルール分の応答を記録する。
     *
     * @return この報告で全ルールが揃ったときだけ true。知らない ID、二度目の ID、
     *         揃った後の報告は false で、状態も変えない。
     */
    @Synchronized
    fun report(ruleId: Long, records: List<T>?, total: Int?): Boolean {
        if (ruleId !in known) return false
        if (!completed.add(ruleId)) return false

        if (records == null || total == null || total < 0) {
            failureCount++
        } else {
            snapshots[ruleId] = Snapshot(records.toList(), total)
        }
        return completed.size == known.size
    }

    /** 成功した応答の控え。未報告・失敗・知らない ID は null。 */
    @Synchronized
    fun snapshot(ruleId: Long): Snapshot<T>? = snapshots[ruleId]

    @Synchronized
    fun isReported(ruleId: Long): Boolean = ruleId in completed

    @Synchronized
    fun needsLookup(mode: String, showEmpty: Boolean): Boolean =
        !isComplete && (mode == RuleOrder.MODE_RECORDING_NEWEST || !showEmpty)

    /** A global first page proves the latest date, not the rule's total recording count. */
    @Synchronized
    fun seed(ruleId: Long, records: List<T>) {
        val latest = records.maxOfOrNull(startAt) ?: return
        if (ruleId !in known || !completed.add(ruleId)) return
        seededLatest[ruleId] = latest
    }

    /** Return false only when the remaining latest recording must be looked up again. */
    @Synchronized
    fun replaceAfterDeletion(ruleId: Long, records: List<T>, total: Int?): Boolean {
        if (ruleId !in known) return false
        seededLatest.remove(ruleId)
        snapshots.remove(ruleId)
        if (records.isEmpty() && total != 0) {
            completed.remove(ruleId)
            return false
        }
        completed.add(ruleId)
        if (total != null) snapshots[ruleId] = Snapshot(records.toList(), total)
        else records.maxOfOrNull(startAt)?.let { seededLatest[ruleId] = it }
        return true
    }

    /** Do not let partial responses gradually reorder the provisional list. */
    @Synchronized
    fun displayOrder(mode: String): List<Long> =
        if (mode == RuleOrder.MODE_RECORDING_NEWEST && !isComplete) {
            val seeded = RuleOrder.sortByLatestRecorded(serverOrder.filter { it in seededLatest }, seededLatest)
            seeded + RuleOrder.provisionalOrder(mode, serverOrder).filterNot { it in seededLatest }
        } else orderedRuleIds(mode)

    /** 表示順に並べたルール ID。並べ替えは [RuleOrder.orderedRuleIds] に任せる。 */
    @Synchronized
    fun orderedRuleIds(mode: String): List<Long> {
        val latestRecordedAt = HashMap<Long, Long>(seededLatest)
        for ((ruleId, snapshot) in snapshots) {
            // limit=1 想定だが、複数件返っても最大の startAt で判定する
            val latest = snapshot.records.maxOfOrNull(startAt) ?: continue
            latestRecordedAt[ruleId] = latest
        }
        return RuleOrder.orderedRuleIds(mode, serverOrder, latestRecordedAt)
    }
}
