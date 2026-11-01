package com.daigorian.epcltvapp

import android.util.Log
import androidx.leanback.widget.ArrayObjectAdapter
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import com.daigorian.epcltvapp.epgstationv2caller.Records
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/** A blank tail card requests one more page. Only [failed] cards show the retry UI. */
data class ManualRecordingContinuation(
    val offset: Long,
    val failed: Boolean = false
)

/**
 * 番組表から録画したもの（ruleId == null）の横ページング。
 *
 * 最初の全録画 1000 件は親 loader が取得済みなので、[acceptSeed] は通信しない。
 * 以降だけ [EpgStationV2.api] を使い、1ページずつ末尾の空カードから読む。
 */
class ManualRecordingPager(
    private val adapter: ArrayObjectAdapter,
    private val isAlive: () -> Boolean,
    private val onContentsChanged: () -> Unit
) {
    private val scan = ManualRecordingScan(
        idOf = { item: RecordedItem -> item.id },
        isManual = { item: RecordedItem -> item.ruleId == null }
    )

    private var closed = false
    private var seedAccepted = false
    private var inFlight: Call<Records>? = null
    private var inFlightOffset: Long? = null
    private var tail: ManualRecordingContinuation? = null
    private var lastSelectedTail: ManualRecordingContinuation? = null

    init {
        if (adapter.size() == 0) {
            adapter.add(RuleRowPlaceholder(-1L, RuleRowPlaceholder.Status.LOADING))
        }
    }

    fun acceptSeed(page: Records?) {
        if (!alive() || seedAccepted) return
        seedAccepted = true

        val result = scan.acceptSeed(page?.let { ManualRecordingScanPage(it.records, it.total) })
        val items = ArrayList<Any>(result.added.size + 1)
        items.addAll(result.added)
        val nextTail: ManualRecordingContinuation? = when {
            result.failed -> ManualRecordingContinuation(scan.nextOffset, failed = true)
            result.hasMore -> ManualRecordingContinuation(scan.nextOffset)
            else -> null
        }
        // Adapter 通知より先に状態を確定する。失敗カードだけは選択済み扱いにする。
        tail = nextTail
        lastSelectedTail = nextTail?.takeIf { result.failed }
        if (nextTail != null) items.add(nextTail)
        replaceContentsWithoutEmptyFrame(items)
        onContentsChanged()
        pump()
    }

    /** true の間は、空カードにフォーカスが来ても API を発行しない。 */
    fun setRuleOrderPending(pending: Boolean) {
        if (closed) return
        val changed = scan.ruleOrderPending != pending
        scan.setRuleOrderPending(pending)
        if (changed) {
            Log.i(TAG, "gate change pending=$pending offset=${scan.nextOffset}")
        }
        if (!pending) pump()
    }

    /** 行内の全カードから呼ばれる想定。空カード以外の選択で直前の同一性を解除する。 */
    fun onSelected(item: Any?) {
        if (!alive()) return
        val continuation = item as? ManualRecordingContinuation
        if (continuation == null || continuation !== tail) {
            lastSelectedTail = null
            return
        }

        if (continuation.failed) {
            lastSelectedTail = continuation
            return
        }
        if (lastSelectedTail === continuation) return

        lastSelectedTail = continuation
        if (inFlight != null) return
        scan.reserveContinuation()
        Log.i(TAG, "request queued offset=${scan.nextOffset} gated=${scan.ruleOrderPending}")
        pump()
    }

    /** エラーカードの決定ボタンから呼ぶ。自動 retry は行わない。 */
    fun retry() {
        if (!alive() || !scan.retry()) return
        val failedTail = tail
        val replacement = ManualRecordingContinuation(failedTail?.offset ?: scan.nextOffset)
        tail = replacement
        // replace に伴う onSelected が来ても、予約済みの1回を二重にしない。
        lastSelectedTail = replacement
        replaceTail(failedTail, replacement)
        onContentsChanged()
        pump()
    }

    fun removeRecorded(id: Long) {
        if (closed) return
        scan.removeRecorded(id)

        var index = adapter.size() - 1
        while (index >= 0) {
            val record = adapter.get(index) as? RecordedItem
            if (record?.id == id) adapter.removeItems(index, 1)
            index--
        }

        val cancelledPage = inFlight != null
        if (cancelledPage) {
            inFlight?.cancel()
            inFlight = null
            inFlightOffset = null
        }

        synchronizeTail()
        if (cancelledPage && !scan.failed && scan.hasMore) {
            // 削除で窓がずれた可能性があるため、古い応答を捨てて同じ続きを引き直す。
            scan.reserveContinuation()
        }
        onContentsChanged()
        if (cancelledPage) pump()
    }

    fun close() {
        closed = true
        inFlight?.cancel()
        inFlight = null
        inFlightOffset = null
        lastSelectedTail = null
    }

    private fun pump() {
        if (!alive() || inFlight != null || !scan.canStartRequest()) return
        val api = EpgStationV2.api
        if (api == null) {
            if (scan.failUnavailable()) {
                synchronizeTail()
                onContentsChanged()
            }
            return
        }

        val offset = scan.takeRequestOffset() ?: return
        Log.i(TAG, "requested offset=$offset")
        val call = api.getRecorded(
            isHalfWidth = true,
            offset = offset,
            limit = PAGE_SIZE,
            isReverse = false
        )
        inFlight = call
        inFlightOffset = offset
        call.enqueue(object : Callback<Records> {
            override fun onResponse(call: Call<Records>, response: Response<Records>) {
                val body = response.body()?.takeIf { response.isSuccessful }
                finishPage(call, offset, body)
            }

            override fun onFailure(call: Call<Records>, t: Throwable) {
                finishPage(call, offset, null)
            }
        })
    }

    private fun finishPage(call: Call<Records>, offset: Long, page: Records?) {
        if (call !== inFlight || inFlightOffset != offset) return
        inFlight = null
        inFlightOffset = null
        if (!alive()) return

        val previousTail = tail
        val previousWasSelected = previousTail != null && lastSelectedTail === previousTail
        val result = scan.acceptPage(offset, page?.let { ManualRecordingScanPage(it.records, it.total) })

        if (!result.failed && result.added.isNotEmpty()) {
            installNonEmptyPage(previousTail, result.added)
        } else {
            installEmptyPage(previousTail, previousWasSelected, result.failed)
        }
        Log.i(
            TAG,
            "page complete count=${result.added.size} offset=$offset nextOffset=${scan.nextOffset} failed=${result.failed}"
        )
        onContentsChanged()
    }

    /**
     * 旧tailの位置を追加先頭recordへ置き換える。選択位置がそのままrecordを指し、
     * 新tailはその後ろへ足すため、選択通知から次ページを連鎖させない。
     */
    private fun installNonEmptyPage(
        previousTail: ManualRecordingContinuation?,
        added: List<RecordedItem>
    ) {
        val nextTail = if (scan.hasMore) ManualRecordingContinuation(scan.nextOffset) else null
        // Adapter を触る前に状態を確定する。focus は先頭追加recordへ残る。
        tail = nextTail
        lastSelectedTail = null

        val tailIndex = indexOfIdentity(previousTail)
        val afterFirst = ArrayList<Any>(added.size + if (nextTail == null) 0 else 1)
        if (added.size > 1) afterFirst.addAll(added.subList(1, added.size))
        if (nextTail != null) afterFirst.add(nextTail)

        if (tailIndex >= 0) {
            adapter.replace(tailIndex, added.first())
            if (afterFirst.isNotEmpty()) adapter.addAll(tailIndex + 1, afterFirst)
        } else {
            adapter.addAll(adapter.size(), added)
            if (nextTail != null) adapter.add(nextTail)
        }
    }

    /** 0追加時は、成功・続きありなら同じ tail インスタンスを残す。 */
    private fun installEmptyPage(
        previousTail: ManualRecordingContinuation?,
        previousWasSelected: Boolean,
        failed: Boolean
    ) {
        if (failed) {
            val errorTail = ManualRecordingContinuation(scan.nextOffset, failed = true)
            tail = errorTail
            if (previousWasSelected) lastSelectedTail = errorTail
            replaceTail(previousTail, errorTail)
            return
        }

        if (scan.hasMore) {
            val nextTail = previousTail?.takeIf { !it.failed }
                ?: ManualRecordingContinuation(scan.nextOffset)
            tail = nextTail
            if (previousWasSelected) lastSelectedTail = nextTail
            if (nextTail !== previousTail) replaceTail(previousTail, nextTail)
            return
        }

        tail = null
        lastSelectedTail = null
        replaceTail(previousTail, null)
    }

    private fun installTail(failed: Boolean, hasMore: Boolean, reuse: ManualRecordingContinuation?) {
        val previous = tail
        val next = when {
            failed -> ManualRecordingContinuation(scan.nextOffset, failed = true)
            hasMore -> reuse ?: ManualRecordingContinuation(scan.nextOffset)
            else -> null
        }
        val previousWasSelected = previous != null && lastSelectedTail === previous
        tail = next
        if (previousWasSelected) lastSelectedTail = next
        replaceTail(previous, next)
    }

    /** 行の外から録画が消えた後に、古い tail を残さず現在の続き状態へ合わせる。 */
    private fun synchronizeTail() {
        if (scan.failed) {
            installTail(failed = true, hasMore = scan.hasMore, reuse = null)
        } else if (scan.hasMore) {
            if (tail == null) installTail(failed = false, hasMore = true, reuse = null)
        } else {
            installTail(failed = false, hasMore = false, reuse = null)
        }
    }

    private fun replaceTail(previous: ManualRecordingContinuation?, next: ManualRecordingContinuation?) {
        val index = indexOfIdentity(previous)
        when {
            previous === next -> Unit
            index >= 0 && next != null -> adapter.replace(index, next)
            index >= 0 && next == null -> adapter.removeItems(index, 1)
            next != null -> adapter.add(next)
        }
    }

    /**
     * 先頭の placeholder を残したまま内容を差し替え、0件フレームを作らない。
     * 録画が1件も無ければ、まとめて読み込む方式と同じく空の行にする。
     */
    private fun replaceContentsWithoutEmptyFrame(items: List<Any>) {
        if (items.isEmpty()) {
            if (adapter.size() > 0) adapter.removeItems(0, adapter.size())
            return
        }
        if (adapter.size() == 0) {
            adapter.addAll(0, items)
            return
        }

        adapter.replace(0, items[0])
        val extras = adapter.size() - 1
        if (extras > 0) adapter.removeItems(1, extras)
        if (items.size > 1) adapter.addAll(1, items.subList(1, items.size))
    }

    private fun indexOfIdentity(item: Any?): Int {
        if (item == null) return -1
        for (index in 0 until adapter.size()) {
            if (adapter.get(index) === item) return index
        }
        return -1
    }

    private fun alive(): Boolean = !closed && isAlive()

    companion object {
        private const val TAG = "ManualRecordingPager"
        private const val PAGE_SIZE = 1000L
    }
}
