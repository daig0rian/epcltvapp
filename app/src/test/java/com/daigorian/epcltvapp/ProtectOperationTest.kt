package com.daigorian.epcltvapp

import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProtectOperation.choicesFor] の回帰テスト。プロテクトダイアログに何を並べ、選ばれたら
 * どの録画へ要求を送るかを確かめる。
 *
 * 番組名はすべて架空のもの。実在の番組名・放送局名は使わない。
 */
class ProtectOperationTest {

    private fun item(id: Long, isProtected: Boolean) = RecordedItem(
        id = id,
        ruleId = null,
        programId = null,
        channelId = null,
        startAt = id * 1_000,
        endAt = id * 1_000 + 500,
        name = "ためしの冒険 #$id",
        description = null,
        extended = null,
        genre1 = null,
        subGenre1 = null,
        genre2 = null,
        subGenre2 = null,
        genre3 = null,
        subGenre3 = null,
        videoType = null,
        videoResolution = null,
        videoStreamContent = null,
        videoComponentType = null,
        audioSamplingRate = null,
        audioComponentType = null,
        isRecording = false,
        thumbnails = null,
        videoFiles = null,
        dropLog = null,
        tags = null,
        isEncoding = false,
        isProtected = isProtected,
    )

    private fun seriesOf(vararg items: RecordedItem) =
        SeriesPlaylist.build(items.map { SeriesEntry.of(it) }, "ためしの冒険")

    private fun choicesFor(current: RecordedItem, series: SeriesPlaylist?) =
        ProtectOperation.choicesFor(current.id, current.name, current.isProtected, series)

    @Test
    fun `シリーズが取れなければ単体の操作だけを出す`() {
        val current = item(1, isProtected = false)

        val choices = choicesFor(current, series = null)

        assertEquals(1, choices.size)
        assertFalse(choices[0].isSeries)
        assertEquals("ためしの冒険 #1", choices[0].targetName)
        assertEquals(listOf(1L), choices[0].idsToChange)
    }

    @Test
    fun `単体の操作は今の状態を反転させる向きになる`() {
        assertTrue(choicesFor(item(1, isProtected = false), null)[0].protect)
        assertFalse(choicesFor(item(1, isProtected = true), null)[0].protect)
    }

    @Test
    fun `1本だけのシリーズではシリーズの操作を出さない`() {
        val current = item(1, isProtected = false)

        val choices = choicesFor(current, seriesOf(current))

        assertEquals(1, choices.size)
        assertFalse(choices[0].isSeries)
    }

    @Test
    fun `全て未プロテクトのシリーズには かける操作だけを出す`() {
        val current = item(1, isProtected = false)

        val choices = choicesFor(current, seriesOf(current, item(2, false), item(3, false)))

        assertEquals(2, choices.size)
        val series = choices[1]
        assertTrue(series.isSeries)
        assertTrue(series.protect)
        assertEquals("ためしの冒険", series.targetName)
        assertEquals(3, series.totalCount)
        assertEquals(listOf(1L, 2L, 3L), series.idsToChange)
    }

    @Test
    fun `全てプロテクト済みのシリーズには 外す操作だけを出す`() {
        val current = item(1, isProtected = true)

        val choices = choicesFor(current, seriesOf(current, item(2, true), item(3, true)))

        assertEquals(2, choices.size)
        val series = choices[1]
        assertTrue(series.isSeries)
        assertFalse(series.protect)
        assertEquals(listOf(1L, 2L, 3L), series.idsToChange)
    }

    @Test
    fun `混ざっているシリーズには両方を出し 状態が変わる録画にだけ送る`() {
        val current = item(2, isProtected = true)

        val choices = choicesFor(
            current,
            seriesOf(item(1, false), current, item(3, false), item(4, true)),
        )

        // 単体・シリーズにかける・シリーズから外す、の順。
        assertEquals(listOf(false, true, true), choices.map { it.isSeries })
        assertEquals(listOf(false, true, false), choices.map { it.protect })
        assertEquals("すでにプロテクト済みの回には送らない", listOf(1L, 3L), choices[1].idsToChange)
        assertEquals("もともと未プロテクトの回には送らない", listOf(2L, 4L), choices[2].idsToChange)
    }

    @Test
    fun `シリーズの本数は すでに目的の状態の回も含めて数える`() {
        val current = item(1, isProtected = false)

        val choices = choicesFor(current, seriesOf(current, item(2, true), item(3, true)))

        // かける操作で状態が変わるのは1本だけでも、見せる本数はシリーズ全体の3本。
        val protectSeries = choices.first { it.isSeries && it.protect }
        assertEquals(3, protectSeries.totalCount)
        assertEquals(listOf(1L), protectSeries.idsToChange)
    }

    @Test
    fun `今開いている回がシリーズに居なくても シリーズの操作は一覧のとおりに出す`() {
        val current = item(9, isProtected = false)

        val choices = choicesFor(current, seriesOf(item(1, false), item(2, false)))

        assertEquals(listOf(9L), choices[0].idsToChange)
        assertEquals(listOf(1L, 2L), choices[1].idsToChange)
        assertEquals(2, choices[1].totalCount)
    }
}
