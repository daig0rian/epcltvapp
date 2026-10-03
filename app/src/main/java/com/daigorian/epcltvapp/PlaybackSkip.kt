package com.daigorian.epcltvapp

/**
 * リモコンの早戻し/早送りキーで飛ぶ先を決める。
 *
 * 再生画面から切り離してあるのは、端の扱い(先頭より前・シークできる上限より先)を
 * 単体テストで押さえておくため。
 */
internal object PlaybackSkip {

    /** 早戻し1回で戻る量。 */
    const val BACKWARD_MS = 10_000L

    /** 早送り1回で進む量。 */
    const val FORWARD_MS = 30_000L

    /**
     * [currentMs] から [deltaMs] だけ動いた先(負なら戻る)。動けないなら null。
     *
     * [maxSeekableMs] はシークで行ける上限で、分からなければ負の値を渡す。
     *
     * 進む側は、**今すでに上限以上に居るなら動かさない**。上限へ丸めるだけだと、上限より先を
     * 再生しているとき(録画TSの終わり15秒がそうなる)に早送りで後ろへ戻ってしまうため。
     * 上限が分からないときも進めない——どこまで行ってよいか決められないため。
     *
     * 戻る側は先頭で止める。上限が分かっていればそれも超えない。
     */
    fun target(currentMs: Long, deltaMs: Long, maxSeekableMs: Long): Long? {
        if (currentMs < 0) return null
        if (deltaMs >= 0) {
            if (maxSeekableMs < 0 || currentMs >= maxSeekableMs) return null
            return (currentMs + deltaMs).coerceAtMost(maxSeekableMs)
        }
        val target = (currentMs + deltaMs).coerceAtLeast(0)
        return if (maxSeekableMs >= 0) target.coerceAtMost(maxSeekableMs) else target
    }
}
