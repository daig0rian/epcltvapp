package com.daigorian.epcltvapp

import androidx.leanback.widget.PlaybackSeekDataProvider

private const val TS_PACKET_SIZE = 188

/**
 * 先頭・末尾プロービング(head/tailの2点)だけを元にLeanbackのシークUIへシーク位置を提供する。
 *
 * 全区間を事前プローブしたテーブルは持たない——duration表示同様、head/tailの2点さえ
 * 分かればシークをすぐ有効化できるため。各シーク位置に対応する正確なバイト位置は、
 * head/tailの線形補間による概算([estimateByteOffset])のみで、実際の値は
 * シーク確定時に [TsProbe.refineSeekPoint] で1回だけ軽量プローブして補正する
 * (PlaybackVideoFragment.performTsSeek参照)。
 */
internal class TsSeekDataProvider(
    val fileSize: Long,
    val pcrPid: Int,
    private val headPoint: TsProbe.TimePoint,
    private val tailPoint: TsProbe.TimePoint,
    pointIntervalMs: Long,
    maxPointCount: Int,
    private val onSeekGestureStarted: () -> Unit,
) : PlaybackSeekDataProvider() {

    /** head起点(0)の相対時刻(ms)。PlayerAdapterのduration/position系と同じ基準。 */
    val durationMs: Long = tailPoint.timeMs - headPoint.timeMs

    private val positions: LongArray = run {
        val pointCount = ((durationMs / pointIntervalMs) + 1).toInt().coerceIn(2, maxPointCount)
        LongArray(pointCount) { i -> durationMs * i / (pointCount - 1) }
    }

    /** ログ表示等、副作用([getSeekPositions]参照)を発生させずに点数だけ知りたい場合用。 */
    val seekPositionCount: Int get() = positions.size

    /**
     * Leanback(PlaybackTransportRowPresenter.startSeek())は、この呼び出し1つ手前で
     * SeekUiClient.onSeekStarted()経由のpause()を必ず発火させ、その直後にこのメソッドを
     * 1回だけ呼ぶ(leanback-1.0.0全体でこのメソッドの呼び出し元はここだけ確認済み)。
     * そのため実質「シークジェスチャー開始」の合図として使え、直前の自動pauseを打ち消す
     * フックとして利用している。返す配列自体は副作用と無関係で変化しない。
     * このメソッドをログ出力等シーク以外の目的で呼び出さないこと([seekPositionCount]を使う)。
     */
    override fun getSeekPositions(): LongArray {
        onSeekGestureStarted()
        return positions
    }

    /** head/tailのバイト位置からの線形補間による概算バイト位置(188アライン済み)。 */
    fun estimateByteOffset(relativeTimeMs: Long): Long {
        if (durationMs <= 0) return headPoint.byteOffset
        val clamped = relativeTimeMs.coerceIn(0, durationMs)
        val span = tailPoint.byteOffset - headPoint.byteOffset
        val raw = headPoint.byteOffset + span * clamped / durationMs
        return raw - (raw % TS_PACKET_SIZE)
    }

    /** TsProbeが返す絶対PCR時刻(ms)を、duration/position系と同じ基準(head起点の相対時刻)に変換する。 */
    fun toRelativeMs(absolutePcrMs: Long): Long = absolutePcrMs - headPoint.timeMs
}
