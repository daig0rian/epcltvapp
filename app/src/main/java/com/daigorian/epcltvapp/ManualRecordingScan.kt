package com.daigorian.epcltvapp

internal data class ManualRecordingScanPage<T>(
    val records: List<T>,
    val total: Int
)

internal data class ManualRecordingScanResult<T>(
    val added: List<T>,
    val hasMore: Boolean,
    val failed: Boolean
)

/**
 * Android に依存しない、番組表からの録画をページ単位で追う状態。
 *
 * offset は raw records の件数だけ進める。EPGStation の total はルール録画を含む
 * 全録画数なので、manual の件数とは比較しない。
 */
internal class ManualRecordingScan<T>(
    private val idOf: (T) -> Long,
    private val isManual: (T) -> Boolean
) {
    private val seenIds = LinkedHashSet<Long>()
    private val tombstonedIds = HashSet<Long>()
    private var continuationRequested = false
    private var requestRunning = false

    var nextOffset: Long = 0L
        private set
    var total: Int? = null
        private set
    var hasMore: Boolean = false
        private set
    var failed: Boolean = false
        private set
    var seedAccepted: Boolean = false
        private set
    var ruleOrderPending: Boolean = true
        private set

    fun setRuleOrderPending(pending: Boolean) {
        ruleOrderPending = pending
    }

    fun reserveContinuation() {
        if (seedAccepted && !failed && hasMore && !requestRunning) continuationRequested = true
    }

    fun canStartRequest(): Boolean =
        seedAccepted && !ruleOrderPending && !failed && hasMore && continuationRequested && !requestRunning

    /** 予約を1回だけ消費する。同一カードへの重複通知でページを重ねない。 */
    fun takeRequestOffset(): Long? {
        if (!canStartRequest()) return null
        continuationRequested = false
        requestRunning = true
        return nextOffset
    }

    fun acceptSeed(page: ManualRecordingScanPage<T>?): ManualRecordingScanResult<T> {
        if (seedAccepted) return snapshot()
        seedAccepted = true
        if (page == null) {
            failed = true
            // total が分からないので、利用者の retry から offset 0 を引き直せる状態にする。
            hasMore = true
            return snapshot()
        }

        return acceptRawPage(requestedOffset = 0L, page = page, compensateSeedDeletions = true)
    }

    fun acceptPage(requestedOffset: Long, page: ManualRecordingScanPage<T>?): ManualRecordingScanResult<T> {
        // 削除などで窓が変わった後に届いた古い応答は反映しない。
        if (requestedOffset != nextOffset) return snapshot(emptyList())
        requestRunning = false
        if (page == null) {
            failed = true
            return snapshot()
        }

        return acceptRawPage(requestedOffset, page)
    }

    /** エラーカードの明示操作だけが呼ぶ。失敗時は同じ offset から1回だけ再試行する。 */
    fun retry(): Boolean {
        if (!failed) return false
        failed = false
        continuationRequested = true
        return true
    }

    fun failUnavailable(): Boolean {
        if (!continuationRequested) return false
        continuationRequested = false
        failed = true
        return true
    }

    /** 遅れて届いた同じ ID を再表示しない。削除で全体窓がずれるため境界も1件戻す。 */
    fun removeRecorded(id: Long): Boolean {
        requestRunning = false
        val firstNotification = tombstonedIds.add(id)
        val wasSeen = seenIds.remove(id)
        if (firstNotification && nextOffset > 0L) nextOffset -= 1L
        if (firstNotification && total != null) {
            total = total!!.let { (it - 1).coerceAtLeast(0) }
        }
        total?.let { hasMore = nextOffset < it }
        return wasSeen
    }

    private fun addManual(items: List<T>): List<T> {
        val added = ArrayList<T>()
        items.forEach { item ->
            val id = idOf(item)
            if (!isManual(item) || id in tombstonedIds || !seenIds.add(id)) return@forEach
            added.add(item)
        }
        return added
    }

    private fun acceptRawPage(
        requestedOffset: Long,
        page: ManualRecordingScanPage<T>,
        compensateSeedDeletions: Boolean = false
    ): ManualRecordingScanResult<T> {
        // A pre-deletion seed may arrive after the deletion notification.
        val removedInPage = if (compensateSeedDeletions) page.records.count { idOf(it) in tombstonedIds } else 0
        total = (page.total - removedInPage).coerceAtLeast(0)
        if (page.records.isEmpty() && total!! > requestedOffset) {
            // 空応答で窓を飛ばすと、次の有効な録画を読み飛ばす。明示 retry に任せる。
            nextOffset = requestedOffset
            hasMore = true
            failed = true
            return snapshot()
        }

        val added = addManual(page.records)
        nextOffset = requestedOffset + page.records.size - removedInPage
        hasMore = nextOffset < total!!
        failed = false
        return ManualRecordingScanResult(added, hasMore, failed)
    }

    private fun snapshot(added: List<T> = emptyList()) =
        ManualRecordingScanResult(added, hasMore, failed)
}
