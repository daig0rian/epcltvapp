package com.daigorian.epcltvapp

import android.util.Log
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import com.daigorian.epcltvapp.epgstationv2caller.Records
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * A real, focusable card for a row that is still loading or failed to load.
 * A row found empty shows no card, the same as the rows loaded all at once.
 */
data class RuleRowPlaceholder(val ruleId: Long, val status: Status) {
    enum class Status { LOADING, ERROR }
}

/** Focusing this blank card requests the next horizontal page. */
data class RuleRowContinuation(val ruleId: Long, val offset: Long)

/** One screen-load session. Only attached cards are inflated; all row identities remain available. */
class LazyRuleRecordingLoader(
    private val ruleIds: List<Long>,
    titles: Map<Long, String>,
    presenter: Presenter,
    headerBase: Long,
    private val isAlive: () -> Boolean,
    private val onRowsReady: (List<ListRow>) -> Unit,
    private val onSeedReady: (Records?) -> Unit,
    private val onRuleOrderPending: (Boolean) -> Unit,
    private val onContentsChanged: () -> Unit
) {
    private class State(val row: ListRow) {
        val records = ArrayList<RecordedItem>()
        val seedBuffer = ArrayDeque<RecordedItem>()
        var offset = 0L
        var total: Int? = null
        var initialLoaded = false
        var failed = false
        /** When the row's first page was last asked of the server (elapsedRealtime). Drives [refreshRows]. */
        var fetchedAt = 0L
    }

    private val states = ruleIds.associateWith { id ->
        val adapter = ArrayObjectAdapter(presenter)
        adapter.add(RuleRowPlaceholder(id, RuleRowPlaceholder.Status.LOADING))
        State(ListRow(HeaderItem(headerBase + id, titles.getValue(id)), adapter))
    }
    private val index = RuleRecordingIndex<RecordedItem>(ruleIds) { it.startAt }
    private val calls = HashSet<Call<Records>>()
    private val indexPending = LinkedHashSet(ruleIds.asReversed())
    private val indexRulesInFlight = HashSet<Long>()
    private val rowInFlight = HashSet<Long>()
    private val rowCalls = HashMap<Long, Call<Records>>()
    private val deletedRecordIds = HashSet<Long>()
    private val viewport = LinkedHashSet<Long>()
    private val pagesRequested = LinkedHashSet<Long>()
    private val refreshRequested = LinkedHashSet<Long>()
    private var disposed = false
    private var indexStarted = false
    private var indexInFlight = 0
    private var indexRequests = 0
    private var mode = RuleOrder.MODE_DEFAULT
    private var showEmpty = true
    private var order: List<Long> = emptyList()
    private var rowsPublished = false
    private var indexStartedAt = 0L
    private var indexOrderPublished = false
    private var seedStarted = false
    private var seedReady = false

    private fun alive() = !disposed && isAlive()

    fun close() {
        disposed = true
        calls.toList().forEach { it.cancel() }
        calls.clear()
    }

    fun setSortMode(value: String) {
        mode = value
        updateOrderGate()
        if (seedReady || value != RuleOrder.MODE_RECORDING_NEWEST) publishRows()
        startSeed()
        if (seedReady && needsIndex()) startIndex()
    }

    fun setShowEmpty(value: Boolean) {
        showEmpty = value
        if (rowsPublished) {
            publishRows(sort = false)
            updateOrderGate()
            if (seedReady && needsIndex()) startIndex()
        }
    }

    private fun needsIndex() = index.needsLookup(mode, showEmpty)

    private fun updateOrderGate() = onRuleOrderPending(needsIndex())

    private fun startSeed() {
        if (seedStarted || !alive()) return
        seedStarted = true
        val call = EpgStationV2.api?.getRecorded(limit = AGGREGATE_PAGE_SIZE, isReverse = false)
        if (call == null) {
            finishSeed(null)
            return
        }
        calls.add(call)
        call.enqueue(object : Callback<Records> {
            override fun onResponse(call: Call<Records>, response: Response<Records>) {
                calls.remove(call)
                if (alive()) finishSeed(response.body()?.takeIf { response.isSuccessful })
            }

            override fun onFailure(call: Call<Records>, t: Throwable) {
                calls.remove(call)
                if (alive()) finishSeed(null)
            }
        })
    }

    private fun finishSeed(body: Records?) {
        seedReady = true
        val records = body?.records.orEmpty().filterNot { it.id in deletedRecordIds }
        // Only a globally newest-first prefix proves that an observed rule's latest date is known.
        val descending = records.zipWithNext().all { (a, b) -> a.startAt >= b.startAt }
        if (body != null && descending) {
            records.filter { it.ruleId != null }.groupBy { it.ruleId!! }.forEach { (id, group) ->
                val state = states[id] ?: return@forEach
                index.seed(id, group)
                if (!state.initialLoaded && !rowInFlight.contains(id)) {
                    state.records.clear()
                    state.records.addAll(group.take(PAGE_SIZE.toInt()))
                    state.seedBuffer.addAll(group.drop(PAGE_SIZE.toInt()))
                    state.offset = state.records.size.toLong()
                    state.initialLoaded = state.records.size >= PAGE_SIZE
                    state.fetchedAt = android.os.SystemClock.elapsedRealtime()
                    // The global response total must not become a per-rule total.
                    render(id)
                }
            }
        }
        Log.i(TAG, "seed ready records=${records.size} knownRules=${index.completedCount} descending=$descending")
        onSeedReady(body)
        publishRows()
        if (needsIndex()) startIndex() else updateOrderGate()
        pumpRows()
    }

    private fun startIndex() {
        if (!indexStarted) {
            indexStarted = true
            indexStartedAt = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "index start rules=${ruleIds.size} known=${index.completedCount} limit=1 concurrency=${EpgStationV2.requestConcurrency}")
        }
        pumpIndex()
    }

    private fun pumpIndex() {
        if (!alive()) return
        if (!needsIndex() && !index.isComplete) return
        val api = EpgStationV2.api ?: return
        var remaining = indexPending.size
        while (indexInFlight < EpgStationV2.requestConcurrency && indexPending.isNotEmpty() && remaining-- > 0) {
            val id = indexPending.first().also { indexPending.remove(it) }
            val state = states.getValue(id)
            if (index.isReported(id) || id in indexRulesInFlight) continue
            if (rowInFlight.contains(id)) {
                indexPending.add(id)
                continue
            }
            // Sorting after browsing must not re-fetch rules whose latest record is already known.
            if (state.initialLoaded && !state.failed && state.total != null &&
                (state.records.isNotEmpty() || state.total == 0)) {
                index.report(id, state.records.take(1), state.total)
                continue
            }
            indexInFlight++
            indexRequests++
            indexRulesInFlight.add(id)
            val call = api.getRecorded(ruleId = id, limit = 1, isReverse = false)
            calls.add(call)
            call.enqueue(object : Callback<Records> {
                override fun onResponse(call: Call<Records>, response: Response<Records>) =
                    finish(response.body()?.takeIf { response.isSuccessful })

                override fun onFailure(call: Call<Records>, t: Throwable) = finish(null)

                private fun finish(body: Records?) {
                    calls.remove(call)
                    if (!alive()) return
                    indexInFlight--
                    indexRulesInFlight.remove(id)
                    val valid = body?.takeUnless {
                        RecordedPageProgress.isUnexpectedEmpty(0, it.records.size, it.total)
                    }
                    index.report(id, valid?.records?.filterNot { it.id in deletedRecordIds }, valid?.total)
                    if (body != null && !state.initialLoaded && !rowInFlight.contains(id)) {
                        state.total = body.total
                        state.offset = body.records.size.toLong()
                        state.records.clear()
                        state.records.addAll(body.records.filterNot { it.id in deletedRecordIds })
                        if (state.records.size != body.records.size) state.offset = 0
                        state.initialLoaded = body.total <= state.records.size
                        state.fetchedAt = android.os.SystemClock.elapsedRealtime()
                        render(id)
                    }
                    if (index.completedCount % 100 == 0 || index.isComplete) {
                        Log.i(TAG, "index progress=${index.completedCount}/${ruleIds.size} failed=${index.failedCount}")
                    }
                    pumpIndex()
                    if (rowsPublished) pumpRows()
                }
            })
        }
        if (index.isComplete && indexInFlight == 0 && !indexOrderPublished) {
            indexOrderPublished = true
            Log.i(TAG, "index complete rules=${ruleIds.size} requests=$indexRequests failed=${index.failedCount} elapsedMs=${android.os.SystemClock.elapsedRealtime() - indexStartedAt}")
            publishRows()
            updateOrderGate()
        }
    }

    fun onConcurrencyChanged() {
        if (indexStarted && !index.isComplete) pumpIndex()
    }

    private fun publishRows(sort: Boolean = true) {
        if (!alive()) return
        val previousOrder = order
        if (sort) order = index.displayOrder(mode)
        val visibleRows = order.mapNotNull { id ->
            val state = states.getValue(id)
            if (!showEmpty && state.total == 0) null else state.row
        }
        rowsPublished = true
        if (previousOrder != order) {
            viewport.clear()
            pagesRequested.clear()
        }
        onRowsReady(visibleRows)
        Log.i(TAG, "rows ready count=${visibleRows.size} mode=$mode complete=${index.isComplete} head=${order.take(5)}")
        onContentsChanged()
    }

    fun updateViewport(ruleIds: Collection<Long>) {
        if (!alive() || !rowsPublished) return
        val next = ruleIds.filterTo(LinkedHashSet()) { states.containsKey(it) }
        if (viewport != next) Log.i(TAG, "viewport rules=$next")
        viewport.clear()
        viewport.addAll(next)
        pagesRequested.retainAll(viewport)
        viewport.forEach { id ->
            val state = states.getValue(id)
            if (state.initialLoaded && !state.failed && !rowInFlight.contains(id) && state.records.size < PAGE_SIZE &&
                state.offset < (state.total ?: 0)) pagesRequested.add(id)
        }
        pumpRows()
    }

    fun onCardSelected(ruleId: Long, item: Any?) {
        val state = states[ruleId] ?: return
        if (!alive() || !rowsPublished) return
        if (item is RuleRowContinuation && item.ruleId == ruleId && item.offset == state.offset &&
            state.initialLoaded && !state.failed && !rowInFlight.contains(ruleId) &&
            (state.total == null || state.offset < state.total!!)) {
            pagesRequested.add(ruleId)
        }
        // Selection can arrive before the deferred viewport sweep.
        viewport.add(ruleId)
        pumpRows()
    }

    fun retry(ruleId: Long) {
        val state = states[ruleId] ?: return
        if (!state.failed) return
        state.failed = false
        viewport.add(ruleId)
        if (state.initialLoaded) pagesRequested.add(ruleId)
        render(ruleId)
        pumpRows()
    }

    /**
     * Re-reads, from the top, the cards already shown in [ruleIds] whose first page is older than [maxAgeMs],
     * so that changes made elsewhere (protection on the details screen, other clients) reach rows that were
     * loaded earlier. The rule order is left as it is. Returns the rules queued.
     */
    fun refreshRows(ruleIds: Collection<Long>, maxAgeMs: Long): List<Long> {
        if (!alive() || !rowsPublished) return emptyList()
        val now = android.os.SystemClock.elapsedRealtime()
        val queued = ruleIds.filter { id ->
            val state = states[id] ?: return@filter false
            state.initialLoaded && !state.failed && now - state.fetchedAt >= maxAgeMs && refreshRequested.add(id)
        }
        // Counted from the request, as the non-lazy rows do, so a sweep during the request does not queue it again.
        queued.forEach { states.getValue(it).fetchedAt = now }
        if (queued.isNotEmpty()) pumpRows()
        return queued
    }

    fun removeRecorded(recordId: Long) {
        if (!deletedRecordIds.add(recordId)) return
        var hideEmpty = false
        states.forEach { (id, state) ->
            if (state.records.none { it.id == recordId } && state.seedBuffer.none { it.id == recordId }) return@forEach
            val displayed = state.records.any { it.id == recordId }
            state.records.removeAll { it.id == recordId }
            state.seedBuffer.removeAll { it.id == recordId }
            if (displayed) state.offset = (state.offset - 1).coerceAtLeast(0)
            state.total = state.total?.let { (it - 1).coerceAtLeast(0) }
            if (!index.replaceAfterDeletion(id, state.records + state.seedBuffer, state.total)) {
                indexPending.add(id)
                indexOrderPublished = false
            }
            if (state.total == 0) hideEmpty = true
            val pending = rowCalls.remove(id)
            if (pending != null) {
                rowInFlight.remove(id)
                calls.remove(pending)
                pending.cancel()
                pagesRequested.add(id)
            }
            if (state.records.isEmpty() && state.total != 0) state.initialLoaded = false
            render(id)
        }
        if (hideEmpty && !showEmpty && rowsPublished) publishRows(sort = false)
        onContentsChanged()
        pumpRows()
        updateOrderGate()
        if (seedReady && needsIndex()) startIndex()
    }

    private fun pumpRows() {
        if (!alive() || !seedReady) return
        val api = EpgStationV2.priorityApi ?: EpgStationV2.api ?: return
        for (id in refreshRequested.toList()) {
            if (rowInFlight.size >= ROW_CONCURRENCY) break
            if (rowInFlight.contains(id) || indexRulesInFlight.contains(id)) continue
            refreshRequested.remove(id)
            val state = states.getValue(id)
            if (state.initialLoaded && !state.failed) requestRefresh(api, id, state)
        }
        val candidates = (pagesRequested + viewport).toList()
        for (id in candidates) {
            if (rowInFlight.size >= ROW_CONCURRENCY) break
            val state = states.getValue(id)
            if (state.failed || rowInFlight.contains(id) || indexRulesInFlight.contains(id) || state.total == 0) continue
            // Deletion elsewhere removes cards through MainMenuAdapter; keep our offset in sync.
            val adapter = state.row.adapter as ArrayObjectAdapter
            val present = (0 until adapter.size()).mapNotNull { (adapter.get(it) as? RecordedItem)?.id }.toSet()
            val removed = state.records.count { it.id !in present }
            if (removed > 0) {
                state.records.removeAll { it.id !in present }
                state.offset = (state.offset - removed).coerceAtLeast(0)
                state.total = state.total?.let { (it - removed).coerceAtLeast(0) }
                render(id)
            }
            val hasMore = state.total == null || state.offset < state.total!!
            if (!hasMore || (state.initialLoaded && !pagesRequested.contains(id))) continue
            pagesRequested.remove(id)
            rowInFlight.add(id)
            if (state.seedBuffer.isNotEmpty()) {
                // Defer adapter notifications out of Leanback's selection/layout callback.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (!alive()) return@post
                    repeat(minOf(PAGE_SIZE.toInt(), state.seedBuffer.size)) {
                        state.records.add(state.seedBuffer.removeFirst())
                        state.offset++
                    }
                    rowInFlight.remove(id)
                    render(id)
                    Log.i(TAG, "row seed page rule=$id count=${state.records.size} remaining=${state.seedBuffer.size}")
                    onContentsChanged()
                    pumpRows()
                }
                continue
            }
            val offset = state.offset
            val limit = RecordedPageProgress.requestLimit(state.records.size, PAGE_SIZE)
            if (offset == 0L) state.fetchedAt = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "row request rule=$id offset=$offset limit=$limit active=${rowInFlight.size}")
            val call = api.getRecorded(ruleId = id, offset = offset, limit = limit, isReverse = false)
            calls.add(call)
            rowCalls[id] = call
            call.enqueue(object : Callback<Records> {
                override fun onResponse(call: Call<Records>, response: Response<Records>) =
                    finish(response.body()?.takeIf { response.isSuccessful })

                override fun onFailure(call: Call<Records>, t: Throwable) = finish(null)

                private fun finish(body: Records?) {
                    calls.remove(call)
                    if (!alive()) return
                    if (rowCalls[id] !== call) return
                    rowCalls.remove(id)
                    rowInFlight.remove(id)
                    if (body == null) {
                        state.failed = true
                    } else {
                        val known = state.records.mapTo(HashSet()) { it.id }
                        state.records.addAll(body.records.filter { it.id !in deletedRecordIds && known.add(it.id) })
                        state.offset = RecordedPageProgress.nextOffset(offset, body.records.size)
                        state.total = body.total
                        state.initialLoaded = true
                        // An inconsistent empty page must not silently skip the remaining recordings.
                        state.failed = RecordedPageProgress.isUnexpectedEmpty(offset, body.records.size, body.total)
                    }
                    render(id)
                    Log.i(TAG, "row complete rule=$id count=${state.records.size} offset=${state.offset} failed=${state.failed}")
                    if (indexStarted && !index.isComplete) pumpIndex()
                    if (!showEmpty && body?.total == 0) publishRows(sort = false)
                    onContentsChanged()
                    pumpRows()
                }
            })
        }
    }

    /** Replaces the row with as many cards as it already shows (at least a page), as the server has them now. */
    private fun requestRefresh(api: EpgStationV2.ApiInterface, id: Long, state: State) {
        val limit = maxOf(state.records.size.toLong(), PAGE_SIZE)
        rowInFlight.add(id)
        Log.i(TAG, "row refresh rule=$id limit=$limit active=${rowInFlight.size}")
        val call = api.getRecorded(ruleId = id, offset = 0, limit = limit, isReverse = false)
        calls.add(call)
        rowCalls[id] = call
        call.enqueue(object : Callback<Records> {
            override fun onResponse(call: Call<Records>, response: Response<Records>) =
                finish(response.body()?.takeIf { response.isSuccessful })

            override fun onFailure(call: Call<Records>, t: Throwable) = finish(null)

            private fun finish(body: Records?) {
                calls.remove(call)
                if (!alive()) return
                if (rowCalls[id] !== call) return
                rowCalls.remove(id)
                rowInFlight.remove(id)
                // A failed refresh keeps what the row already shows.
                if (body != null) {
                    state.records.clear()
                    state.records.addAll(body.records.filter { it.id !in deletedRecordIds })
                    // Later pages come from the server, not from what the startup page left over.
                    state.seedBuffer.clear()
                    state.offset = RecordedPageProgress.nextOffset(0, body.records.size)
                    state.total = body.total
                    render(id)
                    if (!showEmpty && body.total == 0) publishRows(sort = false)
                }
                Log.i(TAG, "row refreshed rule=$id count=${state.records.size} ok=${body != null}")
                onContentsChanged()
                pumpRows()
            }
        })
    }

    private fun render(id: Long) {
        val state = states.getValue(id)
        val adapter = state.row.adapter as ArrayObjectAdapter
        val items: List<Any> = when {
            state.records.isNotEmpty() -> state.records + if (state.failed) {
                listOf(RuleRowPlaceholder(id, RuleRowPlaceholder.Status.ERROR))
            } else if (state.initialLoaded && (state.total == null || state.offset < state.total!!)) {
                listOf(RuleRowContinuation(id, state.offset))
            } else emptyList()
            state.failed -> listOf(RuleRowPlaceholder(id, RuleRowPlaceholder.Status.ERROR))
            // An empty rule leaves an empty row, as the rows loaded all at once do.
            state.total == 0 || (state.initialLoaded && state.offset >= (state.total ?: Int.MAX_VALUE)) -> emptyList()
            else -> listOf(RuleRowPlaceholder(id, RuleRowPlaceholder.Status.LOADING))
        }
        // Keep the selected card in place: replace only the cards that changed, then trim or append.
        val shared = minOf(adapter.size(), items.size)
        for (i in 0 until shared) {
            if (adapter.get(i) != items[i]) adapter.replace(i, items[i])
        }
        if (adapter.size() > shared) adapter.removeItems(shared, adapter.size() - shared)
        if (items.size > shared) adapter.addAll(shared, items.drop(shared))
    }

    companion object {
        private const val TAG = "LazyRuleLoader"
        private const val ROW_CONCURRENCY = 2
        private const val PAGE_SIZE = 12L
        private const val AGGREGATE_PAGE_SIZE = 1000L
    }
}
