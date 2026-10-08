package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** [TsSeekRefiner] の回帰テスト。録画はすべて計算で作った架空のもの。 */
class TsSeekRefinerTest {

    /**
     * 区間ごとにビットレートの違う架空の録画。PCRは40msおきに入っている。
     *
     * @param segments 区間の並び。(長さms, 1msあたりのバイト数)。
     */
    private class FakeStream(segments: List<Pair<Long, Long>>) {
        val points: List<TsProbe.TimePoint>
        var probeCount = 0

        init {
            val list = ArrayList<TsProbe.TimePoint>()
            // PCRの絶対時刻は0から始まらない。0始まりを当てにした計算が紛れないようずらしておく。
            var timeMs = 5_000_000L
            var byteOffset = 0L
            list.add(TsProbe.TimePoint(timeMs, byteOffset))
            for ((lengthMs, bytesPerMs) in segments) {
                var remaining = lengthMs
                while (remaining > 0) {
                    timeMs += PCR_INTERVAL_MS
                    byteOffset += PCR_INTERVAL_MS * bytesPerMs
                    remaining -= PCR_INTERVAL_MS
                    list.add(TsProbe.TimePoint(timeMs, byteOffset - byteOffset % 188))
                }
            }
            points = list
        }

        val head get() = points.first()
        val tail get() = points.last()
        val durationMs get() = tail.timeMs - head.timeMs

        /** [byteOffset] 以降で最初のPCR。アプリの実装と同じ読み方。 */
        fun probe(byteOffset: Long): TsProbe.TimePoint? {
            probeCount++
            var low = 0
            var high = points.size
            while (low < high) {
                val mid = (low + high) / 2
                if (points[mid].byteOffset >= byteOffset) high = mid else low = mid + 1
            }
            return points.getOrNull(low)
        }

        /** 先頭と末尾を結ぶ直線だけで見積もったときの着地のずれ(合わせ込みをしない場合)。 */
        fun straightLineErrorMs(targetMs: Long): Long {
            val guess = head.byteOffset +
                    (tail.byteOffset - head.byteOffset) * (targetMs - head.timeMs) / durationMs
            return probe(guess)!!.timeMs - targetMs
        }

        companion object {
            const val PCR_INTERVAL_MS = 40L
        }
    }

    // ビットレートが前半は高く、中盤で下がり、後半でやや戻る録画(22分)。
    private fun variableStream() = FakeStream(
        listOf(300_000L to 2_300L, 520_000L to 1_500L, 500_000L to 1_900L)
    )

    private fun TsSeekRefiner.seek(stream: FakeStream, targetMs: Long): TsProbe.TimePoint? =
        refine(targetMs, stream.head, stream.tail, isCancelled = { false }, probe = stream::probe)

    @Test
    fun ビットレートが一定なら1回読むだけで着地する() {
        val stream = FakeStream(listOf(1_200_000L to 1_800L))
        val target = stream.head.timeMs + 432_100L
        val landed = TsSeekRefiner().seek(stream, target)!!
        assertTrue(abs(landed.timeMs - target) <= TsSeekRefiner.TOLERANCE_MS)
        assertEquals(1, stream.probeCount)
    }

    @Test
    fun 直線の見積もりだけでは大きくずれる録画を使っている() {
        // 以降のテストが意味を持つための前提の確認。
        val stream = variableStream()
        assertTrue(abs(stream.straightLineErrorMs(stream.head.timeMs + 300_000L)) > 30_000L)
    }

    @Test
    fun ビットレートが変わる録画でも狙った時刻の近くに着地する() {
        val stream = variableStream()
        var target = stream.head.timeMs + 30_000L
        while (target < stream.tail.timeMs - 15_000L) {
            // 表が空の状態(再生を始めて最初のシーク)でも収まることを見るため、毎回作り直す。
            stream.probeCount = 0
            val landed = TsSeekRefiner().seek(stream, target)!!
            assertTrue(
                "target=$target landed=${landed.timeMs}",
                abs(landed.timeMs - target) <= TsSeekRefiner.TOLERANCE_MS
            )
            assertTrue("probes=${stream.probeCount}", stream.probeCount <= TsSeekRefiner.MAX_PROBES)
            target += 30_000L
        }
    }

    @Test
    fun 着地した点のバイト位置はその時刻のものになっている() {
        val stream = variableStream()
        val landed = TsSeekRefiner().seek(stream, stream.head.timeMs + 700_000L)!!
        assertTrue(stream.points.contains(landed))
    }

    @Test
    fun 続けて飛ぶときは多くが1回読むだけで済む() {
        val stream = variableStream()
        val refiner = TsSeekRefiner()
        var position = stream.head.timeMs + 60_000L
        val probesPerSkip = ArrayList<Int>()
        repeat(12) {
            val target = position + 30_000L
            stream.probeCount = 0
            val landed = refiner.seek(stream, target)!!
            assertTrue(abs(landed.timeMs - target) <= TsSeekRefiner.TOLERANCE_MS)
            probesPerSkip.add(stream.probeCount)
            position = landed.timeMs + 3_000L
        }
        // 直前の着地点が手掛かりになるので、ほとんどは1回。ビットレートの変わり目をまたぐ
        // ときだけ増える(この録画では300秒の位置)。
        assertTrue("probes=$probesPerSkip", probesPerSkip.count { it == 1 } >= 9)
        assertTrue("probes=$probesPerSkip", probesPerSkip.all { it <= 3 })
    }

    @Test
    fun すでに読んだ点の近くへは読み直さずに着地する() {
        val stream = variableStream()
        val refiner = TsSeekRefiner()
        val target = stream.head.timeMs + 500_000L
        refiner.seek(stream, target)
        stream.probeCount = 0
        val landed = refiner.seek(stream, target + 200L)!!
        assertEquals(0, stream.probeCount)
        assertTrue(abs(landed.timeMs - (target + 200L)) <= TsSeekRefiner.TOLERANCE_MS)
    }

    @Test
    fun 先頭を狙えば読まずに先頭へ着地する() {
        val stream = variableStream()
        val landed = TsSeekRefiner().seek(stream, stream.head.timeMs)
        assertEquals(stream.head, landed)
        assertEquals(0, stream.probeCount)
    }

    @Test
    fun 範囲の外を狙われたら端に着地する() {
        val stream = variableStream()
        assertEquals(stream.head, TsSeekRefiner().seek(stream, stream.head.timeMs - 10_000L))
        assertEquals(stream.tail, TsSeekRefiner().seek(stream, stream.tail.timeMs + 10_000L))
    }

    @Test
    fun 読めなければ見積もった位置にそのまま着地する() {
        val stream = variableStream()
        val target = stream.head.timeMs + 400_000L
        val landed = TsSeekRefiner().refine(
            target, stream.head, stream.tail, isCancelled = { false }, probe = { null }
        )
        assertNotNull(landed)
        assertEquals(target, landed!!.timeMs)
        assertTrue(landed.byteOffset > stream.head.byteOffset && landed.byteOffset < stream.tail.byteOffset)
        assertEquals(0L, landed.byteOffset % 188)
    }

    @Test
    fun 取り消されたら読まずに打ち切る() {
        val stream = variableStream()
        val landed = TsSeekRefiner().refine(
            stream.head.timeMs + 400_000L, stream.head, stream.tail,
            isCancelled = { true }, probe = stream::probe
        )
        assertNull(landed)
        assertEquals(0, stream.probeCount)
    }

    @Test
    fun 時刻が途中で飛んでいる録画でも読み続けない() {
        val stream = variableStream()
        var probes = 0
        // どこを読んでも、挟んでいる2点より先の時刻が返ってくる。
        val landed = TsSeekRefiner().refine(
            stream.head.timeMs + 400_000L, stream.head, stream.tail, isCancelled = { false }
        ) { byteOffset ->
            probes++
            TsProbe.TimePoint(stream.tail.timeMs + 60_000L, byteOffset)
        }
        assertNotNull(landed)
        assertEquals(1, probes)
    }

    @Test
    fun 追いかけ再生で末尾が伸びても伸びた先へ着地できる() {
        val whole = variableStream()
        val refiner = TsSeekRefiner()
        // 録画の途中(15分の時点)を末尾として一度シークし、そのあと全体を末尾にして先へ飛ぶ。
        val earlierTail = whole.points.first { it.timeMs >= whole.head.timeMs + 900_000L }
        refiner.refine(
            whole.head.timeMs + 600_000L, whole.head, earlierTail,
            isCancelled = { false }, probe = whole::probe
        )
        val target = whole.head.timeMs + 1_200_000L
        val landed = refiner.seek(whole, target)!!
        assertTrue(abs(landed.timeMs - target) <= TsSeekRefiner.TOLERANCE_MS)
    }
}
