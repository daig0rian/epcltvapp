package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [PlaybackSkip] の端の扱いの回帰テスト。 */
class PlaybackSkipTest {

    private val max = 600_000L

    @Test
    fun 進む量だけ先へ飛ぶ() {
        assertEquals(130_000L, PlaybackSkip.target(100_000L, 30_000L, max))
    }

    @Test
    fun 戻る量だけ手前へ飛ぶ() {
        assertEquals(90_000L, PlaybackSkip.target(100_000L, -10_000L, max))
    }

    @Test
    fun 上限を超える早送りは上限で止まる() {
        assertEquals(max, PlaybackSkip.target(max - 5_000L, 30_000L, max))
    }

    @Test
    fun すでに上限に居るなら早送りしない() {
        assertNull(PlaybackSkip.target(max, 30_000L, max))
    }

    @Test
    fun 上限より先を再生しているときの早送りで後ろへ戻らない() {
        // 録画TSは終わりの15秒へシークできないが、通常再生ではそこまで進む。
        assertNull(PlaybackSkip.target(max + 10_000L, 30_000L, max))
    }

    @Test
    fun 上限が分からないときは早送りしない() {
        assertNull(PlaybackSkip.target(100_000L, 30_000L, -1L))
    }

    @Test
    fun 先頭より前へは戻らない() {
        assertEquals(0L, PlaybackSkip.target(4_000L, -10_000L, max))
    }

    @Test
    fun 上限が分からなくても戻れる() {
        assertEquals(90_000L, PlaybackSkip.target(100_000L, -10_000L, -1L))
    }

    @Test
    fun 上限より先からの早戻しは上限を超えない() {
        // 着地予定の位置(シークバーに先に出す値)と実際の着地点を揃えるため。
        assertEquals(max, PlaybackSkip.target(max + 12_000L, -10_000L, max))
    }

    @Test
    fun 位置が取れないときは何もしない() {
        assertNull(PlaybackSkip.target(-1L, 30_000L, max))
        assertNull(PlaybackSkip.target(-1L, -10_000L, max))
    }
}
