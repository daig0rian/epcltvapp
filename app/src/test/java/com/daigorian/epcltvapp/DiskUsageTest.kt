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
        assertEquals("Full", usage.labelBody)
    }

    @Test
    fun `表示が100パーセントに丸まるなら満杯として扱う`() {
        assertTrue(DiskUsage(null, usedBytes = 996, availableBytes = 4).isFull)
        assertFalse(DiskUsage(null, usedBytes = 994, availableBytes = 6).isFull)
        assertEquals(99, DiskUsage(null, usedBytes = 994, availableBytes = 6).usedPercent)
    }

    @Test
    fun `ラベルは使用量と使用率を並べる`() {
        val usage = DiskUsage("recorded", usedBytes = 282 * gb, availableBytes = 153 * gb)

        assertEquals("Used 282 GB · 65%", usage.labelBody)
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
        assertEquals("Used 90.0 GB · 75%", usage.labelBody)
    }

    @Test
    fun `サイズは有効数字3桁ほどで書く`() {
        assertEquals("0 B", DiskUsage.formatSize(0))
        assertEquals("512 B", DiskUsage.formatSize(512))
        assertEquals("1.00 KB", DiskUsage.formatSize(1024))
        assertEquals("1.82 TB", DiskUsage.formatSize((1.82 * 1024 * gb).toLong()))
        assertEquals("45.3 GB", DiskUsage.formatSize((45.3 * gb).toLong()))
        assertEquals("282 GB", DiskUsage.formatSize(282 * gb))
    }

    @Test
    fun `4桁になる手前で次の単位へ上げる`() {
        assertEquals("999 GB", DiskUsage.formatSize(999 * gb))
        assertEquals("0.98 TB", DiskUsage.formatSize(1000 * gb))
        assertEquals("1.00 TB", DiskUsage.formatSize(1024 * gb))
    }

    @Test
    fun `桁の境目で桁数が増えない`() {
        // 9.996 を小数2桁で書くと 10.00、99.96 を小数1桁で書くと 100.0 になってしまう
        assertEquals("10.0 GB", DiskUsage.formatSize((9.996 * gb).toLong()))
        assertEquals("100 GB", DiskUsage.formatSize((99.96 * gb).toLong()))
    }

    @Test
    fun `負のサイズは 0 として書く`() {
        assertEquals("0 B", DiskUsage.formatSize(-1))
    }
}
