package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecordingScanTest {

    @Test fun deletionBeforeSeedResponseDoesNotSkipTheBoundaryRecording() {
        val scan = scan()
        scan.removeRecorded(1)
        val result = scan.acceptSeed(ManualRecordingScanPage(listOf(Item(1, true), Item(2, true)), 3))
        assertEquals(listOf(2L), result.added.map { it.id })
        assertEquals(1L, scan.nextOffset)
        assertEquals(2, scan.total)
        scan.setRuleOrderPending(false)
        scan.reserveContinuation()
        assertEquals(1L, scan.takeRequestOffset())
        val boundary = scan.acceptPage(1, ManualRecordingScanPage(listOf(Item(3, true)), 2))
        assertEquals(listOf(3L), boundary.added.map { it.id })
        assertFalse(boundary.hasMore)
    }

    @Test fun postDeletionSeedDoesNotSubtractTheRemovedIdTwice() {
        val scan = scan()
        scan.removeRecorded(1)
        val result = scan.acceptSeed(ManualRecordingScanPage(listOf(Item(2, true)), 2))
        assertEquals(listOf(2L), result.added.map { it.id })
        assertEquals(1L, scan.nextOffset)
        assertEquals(2, scan.total)
    }

    @Test
    fun inFlight中の再選択は次ページを予約しない() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        scan.acceptSeed(fullPage(5000, Item(1, true)))
        scan.reserveContinuation()
        assertEquals(1000L, scan.takeRequestOffset())
        scan.reserveContinuation()
        assertNull(scan.takeRequestOffset())
        scan.acceptPage(1000, fullPage(5000, Item(2, true)))
        scan.setRuleOrderPending(false)
        assertNull(scan.takeRequestOffset())
        scan.reserveContinuation()
        assertEquals(2000L, scan.takeRequestOffset())
    }

    private data class Item(val id: Long, val manual: Boolean)

    private fun scan() = ManualRecordingScan(
        idOf = { item: Item -> item.id },
        isManual = { item: Item -> item.manual }
    )

    /** 短い raw 応答をそのまま返す。 */
    private fun shortPage(total: Int, vararg items: Item) =
        ManualRecordingScanPage(items.toList(), total)

    /**
     * limit=1000 の通常応答。manual の検証対象だけ先頭に置き、残りは ID 衝突しない
     * 負の ID を持つ rule 録画で埋める。
     */
    private fun fullPage(total: Int, vararg items: Item): ManualRecordingScanPage<Item> {
        val records = ArrayList<Item>(RAW_PAGE_SIZE)
        records.addAll(items)
        var dummyId = -1L
        while (records.size < RAW_PAGE_SIZE) {
            records.add(Item(dummyId, manual = false))
            dummyId--
        }
        return ManualRecordingScanPage(records, total)
    }

    @Test
    fun seedはmanualだけを表示し全体totalで続きを判定する() {
        val scan = scan()
        scan.setRuleOrderPending(false)

        val result = scan.acceptSeed(
            fullPage(2500, Item(1, true), Item(2, false), Item(3, true))
        )

        assertEquals(listOf(1L, 3L), result.added.map { it.id })
        assertEquals(1000L, scan.nextOffset)
        assertTrue(result.hasMore)
        assertTrue(scan.seedAccepted)
    }

    @Test
    fun seedは短rawの件数だけoffsetを進める() {
        val scan = scan()
        scan.setRuleOrderPending(false)

        val result = scan.acceptSeed(
            shortPage(2000, Item(1, true), Item(2, false), Item(3, true))
        )

        assertEquals(listOf(1L, 3L), result.added.map { it.id })
        assertEquals(3L, scan.nextOffset)
        assertTrue(result.hasMore)
        assertFalse(result.failed)
    }

    @Test
    fun seedのraw空でtotalが残るならoffsetを進めずfailedにする() {
        val scan = scan()
        scan.setRuleOrderPending(false)

        val failed = scan.acceptSeed(shortPage(2000))

        assertTrue(failed.failed)
        assertEquals(0L, scan.nextOffset)
        assertTrue(scan.hasMore)
        assertNull(scan.takeRequestOffset())
        assertTrue(scan.retry())
        assertEquals(0L, scan.takeRequestOffset())
    }

    @Test
    fun seedのtotalが小さい正常empty0はfailedにしない() {
        val scan = scan()
        scan.setRuleOrderPending(false)

        val result = scan.acceptSeed(shortPage(0))

        assertTrue(result.added.isEmpty())
        assertFalse(result.failed)
        assertEquals(0L, scan.nextOffset)
        assertFalse(scan.hasMore)
        assertTrue(scan.seedAccepted)
    }

    @Test
    fun ruleOrder完了前は予約だけ残し完了後に一度だけ開始する() {
        val scan = scan()
        scan.acceptSeed(fullPage(5000, Item(1, true)))
        scan.reserveContinuation()

        assertNull(scan.takeRequestOffset())
        scan.setRuleOrderPending(false)
        assertEquals(1000L, scan.takeRequestOffset())
        assertNull(scan.takeRequestOffset())
    }

    @Test
    fun 解除がseedより先でもseed後に同じoffsetから開始する() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        assertNull(scan.takeRequestOffset())

        scan.acceptSeed(fullPage(5000, Item(1, true)))
        scan.reserveContinuation()

        assertEquals(1000L, scan.takeRequestOffset())
    }

    @Test
    fun pageはraw件数だけ進み重複IDを除外する() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        scan.acceptSeed(fullPage(5000, Item(1, true)))
        scan.reserveContinuation()
        assertEquals(1000L, scan.takeRequestOffset())

        val result = scan.acceptPage(
            1000L,
            fullPage(
                5000,
                Item(1, true),
                Item(2, true),
                Item(3, false),
                Item(4, true),
                Item(2, true)
            )
        )

        assertEquals(listOf(2L, 4L), result.added.map { it.id })
        assertEquals(2000L, scan.nextOffset)
        assertTrue(scan.hasMore)
    }

    @Test
    fun pageは短rawの件数だけoffsetを進める() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        scan.acceptSeed(fullPage(5000, Item(1, true)))
        scan.reserveContinuation()
        assertEquals(1000L, scan.takeRequestOffset())

        val result = scan.acceptPage(
            1000L,
            shortPage(5000, Item(10, true), Item(11, false))
        )

        assertEquals(listOf(10L), result.added.map { it.id })
        assertEquals(1002L, scan.nextOffset)
        assertTrue(scan.hasMore)
        assertFalse(result.failed)
    }

    @Test
    fun pageのraw空でtotalが残るならoffsetを進めず明示retryだけ許可する() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        scan.acceptSeed(fullPage(5000, Item(1, true)))
        scan.reserveContinuation()
        assertEquals(1000L, scan.takeRequestOffset())

        val failed = scan.acceptPage(1000L, shortPage(5000))

        assertTrue(failed.failed)
        assertEquals(1000L, scan.nextOffset)
        assertTrue(scan.hasMore)
        assertNull(scan.takeRequestOffset())
        assertTrue(scan.retry())
        assertEquals(1000L, scan.takeRequestOffset())
    }

    @Test
    fun pageのraw空でもtotalがoffset以下なら正常終端にする() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        scan.acceptSeed(fullPage(2000, Item(1, true)))
        scan.reserveContinuation()
        assertEquals(1000L, scan.takeRequestOffset())

        val result = scan.acceptPage(1000L, shortPage(1000))

        assertTrue(result.added.isEmpty())
        assertFalse(result.failed)
        assertEquals(1000L, scan.nextOffset)
        assertFalse(scan.hasMore)
        assertNull(scan.takeRequestOffset())
    }

    @Test
    fun manual0でもrawがrule録画だけならraw件数だけ進める() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        scan.acceptSeed(fullPage(5000, Item(1, true)))
        scan.reserveContinuation()
        assertEquals(1000L, scan.takeRequestOffset())

        val result = scan.acceptPage(1000L, fullPage(5000))

        assertTrue(result.added.isEmpty())
        assertFalse(result.failed)
        assertEquals(2000L, scan.nextOffset)
        assertTrue(scan.hasMore)
        assertNull(scan.takeRequestOffset())
        scan.reserveContinuation()
        assertEquals(2000L, scan.takeRequestOffset())
    }

    @Test
    fun 古いoffsetの応答は反映しない() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        scan.acceptSeed(fullPage(5000, Item(1, true)))
        scan.reserveContinuation()
        scan.takeRequestOffset()
        scan.acceptPage(1000L, fullPage(5000, Item(2, true)))

        val stale = scan.acceptPage(1000L, fullPage(5000, Item(3, true)))

        assertTrue(stale.added.isEmpty())
        assertEquals(2000L, scan.nextOffset)
    }

    @Test
    fun 失敗後は明示retryだけが同じoffsetを許可する() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        scan.acceptSeed(fullPage(5000, Item(1, true)))
        scan.reserveContinuation()
        assertEquals(1000L, scan.takeRequestOffset())

        val failed = scan.acceptPage(1000L, null)

        assertTrue(failed.failed)
        assertNull(scan.takeRequestOffset())
        assertTrue(scan.retry())
        assertEquals(1000L, scan.takeRequestOffset())
        assertFalse(scan.retry())
    }

    @Test
    fun seed失敗のretryはoffset0を使う() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        val failed = scan.acceptSeed(null)

        assertTrue(failed.failed)
        assertTrue(scan.retry())
        assertEquals(0L, scan.takeRequestOffset())
    }

    @Test
    fun tombstoneは遅延応答から除外し表示済みなら窓境界を戻す() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        scan.acceptSeed(fullPage(5000, Item(1, true), Item(2, true)))
        assertTrue(scan.removeRecorded(2L))
        scan.reserveContinuation()
        assertEquals(999L, scan.takeRequestOffset())

        val result = scan.acceptPage(
            999L,
            fullPage(4999, Item(2, true), Item(5, true))
        )

        assertEquals(listOf(5L), result.added.map { it.id })
        assertEquals(1999L, scan.nextOffset)
    }

    @Test
    fun 未表示recordの削除通知でも窓境界を戻して取りこぼしを避ける() {
        val scan = scan()
        scan.setRuleOrderPending(false)
        scan.acceptSeed(fullPage(5000, Item(1, true)))

        assertFalse(scan.removeRecorded(999L))
        scan.reserveContinuation()

        assertEquals(999L, scan.takeRequestOffset())
    }

    companion object {
        private const val RAW_PAGE_SIZE = 1000
    }
}
