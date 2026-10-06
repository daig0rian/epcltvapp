package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [FrameStep] の狙う位置と端の扱いの回帰テスト。 */
class FrameStepTest {

    // 25fps の1コマ。計算を追いやすい値にしてある。
    private val frame = 40_000L
    private val duration = 60_000_000L

    @Test
    fun フレームレートから1コマの長さを求める() {
        assertEquals(40_000L, FrameStep.frameDurationUs(25f))
        assertEquals(33_366L, FrameStep.frameDurationUs(29.97f))
    }

    @Test
    fun フレームレートが不明なら放送と同じ値で代用する() {
        assertEquals(33_366L, FrameStep.frameDurationUs(-1f))
        assertEquals(33_366L, FrameStep.frameDurationUs(0f))
    }

    @Test
    fun 進むときは次のコマの半コマ手前へ飛ぶ() {
        // 1.000秒のコマが出ている。次は1.040秒なので、1.020秒へ飛べばそれが最初に描かれる。
        assertEquals(
            FrameStep.Step(anchorUs = 1_040_000L, seekPositionMs = 1_020L),
            FrameStep.next(1_000_000L, frame, forward = true, durationUs = duration),
        )
    }

    @Test
    fun 戻るときは前のコマの半コマ手前へ飛ぶ() {
        // 前は0.960秒。0.940秒へ飛べば、その前(0.920秒)ではなく0.960秒が最初に描かれる。
        assertEquals(
            FrameStep.Step(anchorUs = 960_000L, seekPositionMs = 940L),
            FrameStep.next(1_000_000L, frame, forward = false, durationUs = duration),
        )
    }

    @Test
    fun 続けて進むと1コマずつ積み上がる() {
        var anchor = 1_000_000L
        repeat(3) {
            anchor = FrameStep.next(anchor, frame, forward = true, durationUs = duration)!!.anchorUs
        }
        assertEquals(1_120_000L, anchor)
    }

    @Test
    fun 進んで戻ると元のコマに戻る() {
        val forward = FrameStep.next(1_000_000L, frame, forward = true, durationUs = duration)!!
        val back = FrameStep.next(forward.anchorUs, frame, forward = false, durationUs = duration)!!
        assertEquals(1_000_000L, back.anchorUs)
    }

    @Test
    fun 最後のコマより先へは進まない() {
        assertNull(FrameStep.next(duration - frame, frame, forward = true, durationUs = duration))
    }

    @Test
    fun 総時間が分からなければ進む側を止めない() {
        assertEquals(
            FrameStep.Step(anchorUs = 1_040_000L, seekPositionMs = 1_020L),
            FrameStep.next(1_000_000L, frame, forward = true, durationUs = -1L),
        )
    }

    @Test
    fun 先頭のコマへ戻るときは位置0へ飛ぶ() {
        assertEquals(
            FrameStep.Step(anchorUs = 0L, seekPositionMs = 0L),
            FrameStep.next(frame, frame, forward = false, durationUs = duration),
        )
    }

    @Test
    fun 先頭のコマより前へは戻らない() {
        assertNull(FrameStep.next(0L, frame, forward = false, durationUs = duration))
    }

    @Test
    fun 起点が先頭から少しずれていても先頭のコマへ戻れる() {
        // 先頭のコマの時刻が0ちょうどでない動画や、起点の誤差を見込んだ余裕。
        assertEquals(
            FrameStep.Step(anchorUs = 0L, seekPositionMs = 0L),
            FrameStep.next(frame - 10_000L, frame, forward = false, durationUs = duration),
        )
    }
}
