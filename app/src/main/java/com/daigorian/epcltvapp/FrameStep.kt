package com.daigorian.epcltvapp

/**
 * 一時停止中のコマ送りで、次に出すコマとそこへ飛ぶためのシーク先を決める。
 *
 * ExoPlayer は一時停止中でも、シークすると**シーク先以降で最初のコマ**を1枚描く。これを使い、
 * 出したいコマの少し手前を狙って飛ぶ。
 *
 * 再生画面から切り離してあるのは、狙う位置の計算と端の扱いを単体テストで押さえておくため。
 */
internal object FrameStep {

    // フレームレートが取れない動画で仮定する値(放送と同じ 29.97fps)。
    private const val DEFAULT_FRAME_RATE = 30_000f / 1_001f

    /** 1コマの長さ(us)。[frameRate] が正でなければ(=不明)既定のフレームレートで代用する。 */
    fun frameDurationUs(frameRate: Float): Long =
        (1_000_000 / (if (frameRate > 0f) frameRate else DEFAULT_FRAME_RATE)).toLong()

    /**
     * @param anchorUs 出すコマの時刻。続けて押されたときの次の起点になる。
     * @param seekPositionMs そのコマを出すために飛ぶ位置。
     */
    data class Step(val anchorUs: Long, val seekPositionMs: Long)

    /**
     * 今 [anchorUs] のコマが出ているときの、次(または前)の1コマ。動けないなら null。
     *
     * シーク先は出したいコマの**半コマ手前**にする。ちょうどの時刻を狙うと、フレームレートの
     * 端数やミリ秒への丸めで1つ隣のコマに転ぶため、前後に半コマずつ余裕を持たせる。
     *
     * [durationUs] は総時間で、分からなければ負の値を渡す(その場合は進む側を止めない)。
     */
    fun next(anchorUs: Long, frameDurationUs: Long, forward: Boolean, durationUs: Long): Step? {
        val half = frameDurationUs / 2
        if (forward) {
            val target = anchorUs + frameDurationUs
            if (durationUs >= 0 && target >= durationUs) return null
            return Step(target, (target - half) / 1000)
        }
        val target = anchorUs - frameDurationUs
        // 先頭のコマより前には何も無い。起点の誤差を見込んで、半コマまでは先頭のコマとみなす。
        if (target < -half) return null
        return Step(target.coerceAtLeast(0), (target - half).coerceAtLeast(0) / 1000)
    }
}
