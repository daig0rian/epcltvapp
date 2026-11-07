package com.daigorian.epcltvapp

import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import com.daigorian.epcltvapp.epgstationv2caller.VideoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SeriesPlaylist] のうち、ネットワークに触れない部分の回帰テスト。
 *
 * 番組名はすべて架空のもの。実在の番組名・放送局名は使わない。
 */
class SeriesPlaylistTest {

    private fun ts(id: Long) = VideoFile(id, "TS", "ep-$id.m2ts", "ts", 0)

    private fun encoded(id: Long, name: String) = VideoFile(id, name, "ep-$id.mp4", "encoded", 0)

    private fun program(
        id: Long,
        startAt: Long,
        name: String,
        videoFiles: List<VideoFile>? = listOf(ts(id * 10)),
        isProtected: Boolean = false,
    ) = RecordedItem(
        id = id,
        ruleId = null,
        programId = null,
        channelId = null,
        startAt = startAt,
        endAt = startAt + 500,
        name = name,
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
        videoFiles = videoFiles,
        dropLog = null,
        tags = null,
        isEncoding = false,
        isProtected = isProtected,
    )

    private fun playlistOf(vararg programs: RecordedItem) =
        SeriesPlaylist.build(programs.map { SeriesEntry.of(it) }, "ためしの冒険")

    @Test
    fun `録画された順に並び、次の回を返す`() {
        // わざと録画順と違う順で渡す(検索結果は新しい順に返ってくるため)。
        val playlist = playlistOf(
            program(3, 3_000, "ためしの冒険 #3"),
            program(1, 1_000, "ためしの冒険 #1"),
            program(2, 2_000, "ためしの冒険 #2"),
        )

        assertEquals(2L, playlist.next(1)?.id)
        assertEquals(3L, playlist.next(2)?.id)
        assertEquals(listOf(1L, 2L, 3L), playlist.entries.map { it.id })
    }

    @Test
    fun `最後に録画された回の次は無い`() {
        val playlist = playlistOf(
            program(1, 1_000, "ためしの冒険 #1"),
            program(2, 2_000, "ためしの冒険 #2"),
        )

        assertNull(playlist.next(2))
    }

    @Test
    fun `一覧に居ない回の次は決められない`() {
        val playlist = playlistOf(program(1, 1_000, "ためしの冒険 #1"))

        assertNull(playlist.next(99))
        assertEquals(-1, playlist.indexOf(99))
    }

    @Test
    fun `1本だけのシリーズには次が無い`() {
        val playlist = playlistOf(program(1, 1_000, "ためしの冒険 #1"))

        assertNull(playlist.next(1))
    }

    @Test
    fun `同時刻に録画された回はIDの順に並ぶ`() {
        val playlist = playlistOf(
            program(2, 1_000, "ためしの冒険 #2"),
            program(1, 1_000, "ためしの冒険 #1"),
        )

        assertEquals(2L, playlist.next(1)?.id)
    }

    @Test
    fun `番組名にシリーズ名を含むものだけがシリーズとみなされる`() {
        // EPGStationのキーワード検索は番組概要にも当たるため、番組名で絞り直す。
        assertTrue(SeriesPlaylist.matchesSeries("ためしの冒険 #4", "ためしの冒険"))
        assertFalse(
            "概要にシリーズ名が出てくるだけの無関係な番組は外す",
            SeriesPlaylist.matchesSeries("架空バラエティ発表会", "ためしの冒険")
        )
    }

    @Test
    fun `全角と半角の違いはシリーズ名の一致を妨げない`() {
        // サーバー側は半角に寄せてから突き合わせるので、こちらだけ厳密だと食い違う。
        assertTrue(SeriesPlaylist.matchesSeries("ＴＥＳＴ冒険譚 #2", "TEST冒険譚"))
        assertTrue(SeriesPlaylist.matchesSeries("TEST冒険譚 #3", "ＴＥＳＴ冒険譚"))
    }

    @Test
    fun `連続再生用の取得は前後の回が分かった時点で打ち切る`() {
        // 検索結果は新しい順に返る。今見ている回(2)と、それより古い回(1)が揃っている。
        val collected = listOf(
            SeriesEntry.of(program(2, 2_000, "ためしの冒険 #2")),
            SeriesEntry.of(program(1, 1_000, "ためしの冒険 #1")),
        )

        assertFalse(
            SeriesPlaylist.shouldFetchMore(
                collected, currentId = 2, fetchedCount = 100, total = 300, page = 0, fetchAll = false
            )
        )
    }

    @Test
    fun `全件の取得は検索結果が尽きるまで続ける`() {
        val collected = listOf(
            SeriesEntry.of(program(2, 2_000, "ためしの冒険 #2")),
            SeriesEntry.of(program(1, 1_000, "ためしの冒険 #1")),
        )

        assertTrue(
            "前後の回が揃っていても、残りがあるうちは取りに行く",
            SeriesPlaylist.shouldFetchMore(
                collected, currentId = 2, fetchedCount = 100, total = 300, page = 0, fetchAll = true
            )
        )
        assertFalse(
            SeriesPlaylist.shouldFetchMore(
                collected, currentId = 2, fetchedCount = 300, total = 300, page = 2, fetchAll = true
            )
        )
    }

    @Test
    fun `プロテクトの状態を読める`() {
        assertTrue(SeriesEntry.of(program(1, 1_000, "ためしの冒険 #1", isProtected = true)).isProtected)
        assertFalse(SeriesEntry.of(program(2, 2_000, "ためしの冒険 #2")).isProtected)
    }

    @Test
    fun `次の回では今と同じ種類のファイルを選ぶ`() {
        val entry = SeriesEntry.of(
            program(
                1, 1_000, "ためしの冒険 #2",
                videoFiles = listOf(ts(10), encoded(11, "720p")),
            )
        )

        val ts = entry.resolvePlaybackTarget(preferTs = true, preferredName = null)
        assertTrue("TSを見ていたなら次もTS", ts!!.isTs)
        assertEquals(10L, ts.actionId)

        val encoded = entry.resolvePlaybackTarget(preferTs = false, preferredName = "720p")
        assertFalse("エンコード済みを見ていたなら次もエンコード済み", encoded!!.isTs)
        assertEquals(11L, encoded.actionId)
    }

    @Test
    fun `同じ画質が無ければ有るものへ落とす`() {
        val tsOnly = SeriesEntry.of(program(1, 1_000, "ためしの冒険 #2", videoFiles = listOf(ts(10))))
        val encodedOnly = SeriesEntry.of(
            program(
                2, 2_000, "ためしの冒険 #3",
                videoFiles = listOf(encoded(21, "480p")),
            )
        )

        // エンコード済みを見ていても、次の回にTSしか無ければTSで再生する。
        val fallbackToTs = tsOnly.resolvePlaybackTarget(preferTs = false, preferredName = "720p")
        assertTrue(fallbackToTs!!.isTs)

        // TSを見ていても、次の回にエンコード済みしか無ければそれで再生する。
        val fallbackToEncoded = encodedOnly.resolvePlaybackTarget(preferTs = true, preferredName = "720p")
        assertFalse(fallbackToEncoded!!.isTs)
        assertEquals(21L, fallbackToEncoded.actionId)
    }

    @Test
    fun `画質は今見ているものと同じ名前を優先する`() {
        val entry = SeriesEntry.of(
            program(
                1, 1_000, "ためしの冒険 #2",
                videoFiles = listOf(encoded(31, "480p"), encoded(32, "720p")),
            )
        )

        assertEquals(32L, entry.resolvePlaybackTarget(preferTs = false, preferredName = "720p")?.actionId)
        // 同じ名前が無ければ先頭のものを使う。
        assertEquals(31L, entry.resolvePlaybackTarget(preferTs = false, preferredName = "1080p")?.actionId)
    }

    @Test
    fun `再生できるファイルが無ければ再生対象を返さない`() {
        val entry = SeriesEntry.of(program(1, 1_000, "ためしの冒険 #2", videoFiles = null))

        assertNull(entry.resolvePlaybackTarget(preferTs = true, preferredName = null))
    }
}
