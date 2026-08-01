package com.daigorian.epcltvapp

import androidx.leanback.widget.PlaybackSeekDataProvider
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

private const val SEEK_POINT_INTERVAL_MS = 15_000L
private const val SEEK_POINT_COUNT_MAX = 400

/**
 * TS以外(エンコード済み直接再生・HLS)向けの汎用シークデータプロバイダ。
 * TsSeekDataProviderと異なりバイトオフセットの概算は不要——ExoPlayerがdurationを
 * ネイティブに把握しており、seekTo(ms)もそのまま使えるため、durationを均等割りした
 * 位置をその場で返すだけでよい。
 */
@UnstableApi
internal class DurationSeekDataProvider(
    private val player: ExoPlayer,
    private val onSeekGestureStarted: () -> Unit,
) : PlaybackSeekDataProvider() {

    /**
     * Leanback(PlaybackTransportRowPresenter.startSeek())は、この呼び出し1つ手前で
     * SeekUiClient.onSeekStarted()経由のpause()を必ず発火させ、その直後にこのメソッドを
     * 1回だけ呼ぶ(TsSeekDataProvider.getSeekPositions()参照、leanback-1.0.0のソースで確認済み)。
     * そのため実質「シークジェスチャー開始」の合図として使え、直前の自動pauseを打ち消す
     * フックとして利用している。
     */
    override fun getSeekPositions(): LongArray {
        onSeekGestureStarted()
        val durationMs = player.duration
        if (durationMs == C.TIME_UNSET || durationMs <= 0) {
            // durationが未確定な間にシークジェスチャーが始まった場合の保険。
            // 次にシークジェスチャーが始まる時点(=このメソッドが再度呼ばれる時点)には
            // 大抵durationが確定しているため、再計算されて解消する。
            return longArrayOf(player.currentPosition)
        }
        val pointCount = ((durationMs / SEEK_POINT_INTERVAL_MS) + 1).toInt().coerceIn(2, SEEK_POINT_COUNT_MAX)
        return LongArray(pointCount) { i -> durationMs * i / (pointCount - 1) }
    }
}
