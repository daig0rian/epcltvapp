package com.daigorian.epcltvapp

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max

private const val TS_PACKET_SIZE = 188

/**
 * 録画TSのシークで、狙った時刻に着地するバイト位置を探す。
 *
 * 録画TSは時刻とバイト位置の対応表を持っていない。先頭と末尾の2点を結ぶ直線で見積もるだけだと、
 * ビットレートが一定でない録画では着地が数十秒ずれる。そこで、見積もった位置の実時刻を読み
 * (プローブ)、ずれていたらその点を足して見積もり直す——を、狙いの近くに来るまで繰り返す。
 *
 * 読めた点は覚えておき、以後のシークの見積もりにも使う。近くへ飛ぶほど手掛かりが増えるので、
 * 早戻し/早送りを繰り返す使い方では多くが1回のプローブで済む。
 *
 * スレッドセーフではない。1つのスレッド(プローブ用)からだけ使うこと。
 */
internal class TsSeekRefiner {

    // 時刻(ms) → バイト位置。先頭・末尾と、これまでに読めた点。
    private val known = TreeMap<Long, Long>()

    /** 直近の [refine] でプローブした回数。ログ用。 */
    var lastProbeCount = 0
        private set

    /**
     * [targetMs] に着地する点を返す。途中で [isCancelled] が true になったら打ち切って null。
     *
     * 時刻はすべて [TsProbe.TimePoint] と同じ基準(PCRの絶対時刻)で渡す。
     *
     * @param head 先頭の点。
     * @param tail 末尾の点。追いかけ再生では呼ぶたびに伸びていてよい。
     * @param probe 渡したバイト位置以降で最初に見つかる点を返す。読めなければ null。
     */
    fun refine(
        targetMs: Long,
        head: TsProbe.TimePoint,
        tail: TsProbe.TimePoint,
        isCancelled: () -> Boolean,
        probe: (byteOffset: Long) -> TsProbe.TimePoint?,
    ): TsProbe.TimePoint? {
        known[head.timeMs] = head.byteOffset
        known[tail.timeMs] = tail.byteOffset
        lastProbeCount = 0
        while (true) {
            val lo = known.floorEntry(targetMs)
            val hi = known.ceilingEntry(targetMs)
            val nearest = nearest(targetMs, lo, hi)
            // 先頭より前・末尾より後ろを狙われたら、端に着地する。
            if (lo == null || hi == null) return nearest
            if (abs(nearest.timeMs - targetMs) <= TOLERANCE_MS || lastProbeCount == MAX_PROBES) {
                return nearest
            }
            val guess = guess(targetMs, lo, hi)
            // 挟んでいる2点の間に、読む場所がもう残っていない。
            if (guess <= lo.value || guess >= hi.value) return nearest
            if (isCancelled()) return null
            lastProbeCount++
            // 読めなければ、見積もった位置にそのまま着地する(何もしないよりまし)。
            val point = probe(guess) ?: return TsProbe.TimePoint(targetMs, guess)
            // 挟んでいる2点の外の時刻が返ってきた(PCRが途中で飛んでいる録画など)。時刻順という
            // 前提が崩れているので表には足さず、読めた場所にそのまま着地する。
            if (point.timeMs <= lo.key || point.timeMs >= hi.key) return point
            known[point.timeMs] = point.byteOffset
        }
    }

    private fun nearest(
        targetMs: Long,
        lo: Map.Entry<Long, Long>?,
        hi: Map.Entry<Long, Long>?,
    ): TsProbe.TimePoint {
        val entry = when {
            lo == null -> hi!!
            hi == null -> lo
            targetMs - lo.key <= hi.key - targetMs -> lo
            else -> hi
        }
        return TsProbe.TimePoint(entry.key, entry.value)
    }

    /**
     * 次に読むバイト位置。狙いに近い2点を結ぶ直線から見積もる。
     *
     * 狙いを挟む2点([lo]・[hi])のほか、片側に並んだ隣り合う2点を延長した線も候補にする。
     * 挟む2点の片方が遠いとき(直前の着地点と末尾、など)は、遠い点までの平均ビットレートより
     * 近くの2点の傾きのほうが当てになる。延長に使う2点が近すぎると傾きが荒れるので、
     * [MIN_SLOPE_SPAN_MS] 以上離れている組だけを使う。
     */
    private fun guess(targetMs: Long, lo: Map.Entry<Long, Long>, hi: Map.Entry<Long, Long>): Long {
        var a = lo
        var b = hi
        var reach = max(targetMs - lo.key, hi.key - targetMs)
        known.lowerEntry(lo.key)?.let { below ->
            if (lo.key - below.key >= MIN_SLOPE_SPAN_MS && targetMs - below.key < reach) {
                a = below
                b = lo
                reach = targetMs - below.key
            }
        }
        known.higherEntry(hi.key)?.let { above ->
            if (above.key - hi.key >= MIN_SLOPE_SPAN_MS && above.key - targetMs < reach) {
                a = hi
                b = above
            }
        }
        var guess = onLine(targetMs, a, b)
        // 延長した線が挟む2点の外を指したら、挟む2点の間に戻す。
        if (guess <= lo.value || guess >= hi.value) guess = onLine(targetMs, lo, hi)
        return guess - guess % TS_PACKET_SIZE
    }

    private fun onLine(targetMs: Long, a: Map.Entry<Long, Long>, b: Map.Entry<Long, Long>): Long =
        a.value + (b.value - a.value) * (targetMs - a.key) / (b.key - a.key)

    companion object {
        // 着地のずれをここまで許す。映像は着地点の次のキーフレーム(放送では0.5秒おき)から
        // 出るので、これより詰めても見た目は変わらない。
        const val TOLERANCE_MS = 500L
        // 1回のシークで読む回数の上限。実際の録画で試した範囲では、表が空でも4回で収まる。
        const val MAX_PROBES = 6
        private const val MIN_SLOPE_SPAN_MS = 2_000L
    }
}
