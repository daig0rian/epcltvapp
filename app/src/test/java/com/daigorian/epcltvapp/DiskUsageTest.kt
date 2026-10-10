package com.daigorian.epcltvapp

import com.daigorian.epcltvapp.epgstationcaller.StorageInfoV1
import com.daigorian.epcltvapp.epgstationv2caller.StorageInfo
import com.daigorian.epcltvapp.epgstationv2caller.StorageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiskUsage] の回帰テスト。ホームのタイトル文字の下に出す棒の、長さとラベルを確かめる。
 */
class DiskUsageTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `使用率は使用済みと空きの合計を分母にする`() {
        // 全体は 100GB だが、予約領域が 5GB あり、使用済み + 空きは 95GB にしかならない
        val usage = DiskUsage.fromV2(
            StorageInfo(listOf(StorageItem("recorded", available = 19 * gb, used = 76 * gb, total = 100 * gb)))
        ).single()

        assertEquals(0.8f, usage.usedRatio, 0.0001f)
        assertEquals(80, usage.usedPercent)
    }

    @Test
    fun `空きが無くなると満杯になる`() {
        // 全体を分母にすると 95% 止まりで、満杯にならない
        val usage = DiskUsage.fromV2(
            StorageInfo(listOf(StorageItem("recorded", available = 0, used = 95 * gb, total = 100 * gb)))
        ).single()

        assertEquals(1f, usage.usedRatio, 0f)
        assertTrue(usage.isFull)
        assertEquals("Full 95.0GB/95.0GB", usage.labelBody)
    }

    @Test
    fun `表示が100パーセントに丸まるなら満杯として扱う`() {
        assertTrue(DiskUsage(null, usedBytes = 996, availableBytes = 4).isFull)
        assertFalse(DiskUsage(null, usedBytes = 994, availableBytes = 6).isFull)
        assertEquals(99, DiskUsage(null, usedBytes = 994, availableBytes = 6).usedPercent)
    }

    @Test
    fun `ラベルは使用量を容量との分数で書き、使用率を添える`() {
        val usage = DiskUsage("recorded", usedBytes = 282 * gb, availableBytes = 153 * gb)

        assertEquals("Used 282GB/435GB · 65%", usage.labelBody)
    }

    @Test
    fun `ほとんど使っていない保存先でも容量が読める`() {
        val usage = DiskUsage("archive", usedBytes = 28 * 1024, availableBytes = 1771 * gb)

        assertEquals("Used 0.00TB/1.73TB · 0%", usage.labelBody)
    }

    @Test
    fun `容量は使用済みと空きの合計で、予約領域を含まない`() {
        val usage = DiskUsage.fromV2(
            StorageInfo(listOf(StorageItem("recorded", available = 19 * gb, used = 76 * gb, total = 100 * gb)))
        ).single()

        assertEquals(95 * gb, usage.capacityBytes)
        assertEquals("Used 76.0GB/95.0GB · 80%", usage.labelBody)
    }

    @Test
    fun `保存先が1つなら名前は出さない`() {
        val usage = DiskUsage("recorded", usedBytes = gb, availableBytes = gb)

        assertEquals("Disk", usage.labelName(entryCount = 1))
    }

    @Test
    fun `保存先が複数なら保存先名で見分ける`() {
        val usage = DiskUsage("recorded", usedBytes = gb, availableBytes = gb)

        assertEquals("recorded", usage.labelName(entryCount = 2))
    }

    @Test
    fun `名前が無い保存先は複数あっても既定の語になる`() {
        assertEquals("Disk", DiskUsage(null, usedBytes = gb, availableBytes = gb).labelName(entryCount = 2))
        assertEquals("Disk", DiskUsage(" ", usedBytes = gb, availableBytes = gb).labelName(entryCount = 2))
    }

    @Test
    fun `v2 は応答の順に並べる`() {
        val usages = DiskUsage.fromV2(
            StorageInfo(
                listOf(
                    StorageItem("first", available = gb, used = gb, total = 2 * gb),
                    StorageItem("second", available = 3 * gb, used = gb, total = 4 * gb),
                )
            )
        )

        assertEquals(listOf("first", "second"), usages.map { it.name })
        assertEquals(listOf(50, 25), usages.map { it.usedPercent })
    }

    @Test
    fun `v2 は上限の本数までしか出さない`() {
        val items = (1..6).map { StorageItem("disk$it", available = gb, used = gb, total = 2 * gb) }

        val usages = DiskUsage.fromV2(StorageInfo(items))

        assertEquals(listOf("disk1", "disk2", "disk3", "disk4"), usages.map { it.name })
    }

    @Test
    fun `値の欠けた保存先は出さない`() {
        val usages = DiskUsage.fromV2(
            StorageInfo(
                listOf(
                    StorageItem("empty", available = 0, used = 0, total = 0),
                    StorageItem("negative", available = -1, used = gb, total = gb),
                    StorageItem("ok", available = gb, used = gb, total = 2 * gb),
                )
            )
        )

        assertEquals(listOf("ok"), usages.map { it.name })
    }

    @Test
    fun `応答が空なら何も出さない`() {
        assertEquals(emptyList<DiskUsage>(), DiskUsage.fromV2(null))
        assertEquals(emptyList<DiskUsage>(), DiskUsage.fromV2(StorageInfo(null)))
        assertEquals(emptyList<DiskUsage>(), DiskUsage.fromV2(StorageInfo(emptyList())))
        assertEquals(emptyList<DiskUsage>(), DiskUsage.fromV1(null))
        assertEquals(emptyList<DiskUsage>(), DiskUsage.fromV1(StorageInfoV1(free = 0, used = 0, total = 0)))
    }

    @Test
    fun `v1 は名前の無い保存先1つになる`() {
        val usage = DiskUsage.fromV1(StorageInfoV1(free = 30 * gb, used = 90 * gb, total = 125 * gb)).single()

        assertEquals(null, usage.name)
        assertEquals(75, usage.usedPercent)
        assertEquals("Used 90GB/120GB · 75%", usage.labelBody)
    }

    @Test
    fun `容量は有効数字3桁ほどで書く`() {
        assertEquals("100B/512B", DiskUsage.formatAmount(100, 512))
        assertEquals("0.50KB/1.00KB", DiskUsage.formatAmount(512, 1024))
        assertEquals("12.3GB/45.3GB", DiskUsage.formatAmount((12.34 * gb).toLong(), (45.3 * gb).toLong()))
        assertEquals("282GB/435GB", DiskUsage.formatAmount(282 * gb, 435 * gb))
        assertEquals("0.31TB/1.82TB", DiskUsage.formatAmount((0.31 * 1024 * gb).toLong(), (1.82 * 1024 * gb).toLong()))
    }

    @Test
    fun `使用量の単位と桁は容量に合わせる`() {
        // 使用量だけで選ぶと 3.00KB や 1.50GB になり、容量と食い違う
        assertEquals("0GB/500GB", DiskUsage.formatAmount(3 * 1024, 500 * gb))
        assertEquals("2GB/500GB", DiskUsage.formatAmount((1.5 * gb).toLong(), 500 * gb))
        assertEquals("0.00TB/1.82TB", DiskUsage.formatAmount(3 * gb, (1.82 * 1024 * gb).toLong()))
        assertEquals("0.25TB/1.82TB", DiskUsage.formatAmount(256 * gb, (1.82 * 1024 * gb).toLong()))
    }

    @Test
    fun `4桁になる手前で次の単位へ上げる`() {
        assertEquals("500GB/999GB", DiskUsage.formatAmount(500 * gb, 999 * gb))
        assertEquals("0.49TB/0.98TB", DiskUsage.formatAmount(500 * gb, 1000 * gb))
        assertEquals("0.50TB/1.00TB", DiskUsage.formatAmount(512 * gb, 1024 * gb))
    }

    @Test
    fun `桁の境目で桁数が増えない`() {
        // 9.996 を小数2桁で書くと 10.00、99.96 を小数1桁で書くと 100.0 になってしまう
        assertEquals("5.0GB/10.0GB", DiskUsage.formatAmount(5 * gb, (9.996 * gb).toLong()))
        assertEquals("50GB/100GB", DiskUsage.formatAmount(50 * gb, (99.96 * gb).toLong()))
    }

    @Test
    fun `負の値は 0 として書く`() {
        assertEquals("0GB/100GB", DiskUsage.formatAmount(-1, 100 * gb))
        assertEquals("0B/0B", DiskUsage.formatAmount(-1, -1))
    }
}
