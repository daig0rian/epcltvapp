package com.daigorian.epcltvapp

import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import androidx.leanback.media.PlaybackGlueHost
import androidx.leanback.media.PlayerAdapter
import androidx.leanback.media.SurfaceHolderGlueHost
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer

/**
 * androidx.media3.ui.leanback.LeanbackPlayerAdapter は final のため継承できない。
 * 収録済みTS再生では duration/position を ExoPlayer 自身の値ではなく、
 * TsProbe で確定した実測値 + 疑似シーク時のオフセットで管理する必要があるため、
 * 同等のロジックを持つアダプタをここで自前実装する。
 *
 * TsReadexDataSource は tsreadex の逐次処理前提のため常に C.LENGTH_UNSET/C.TIME_UNSET を
 * 返す。そのため ExoPlayer 自身は「今開いているバイト範囲の先頭からの経過時間」しか
 * 知らない。実際の再生位置は [positionOffsetMs]（疑似シークで着地した論理時刻）に
 * ExoPlayer 側の経過時間を足したものになる。
 */
@UnstableApi
internal class TsSeekPlayerAdapter(
    private val player: ExoPlayer,
    private val updatePeriodMs: Int,
    private val onSeekRequested: (positionMs: Long) -> Unit,
) : PlayerAdapter(), Runnable {

    private val handler = Handler(Looper.getMainLooper())
    private val playerListener = PlayerEventListener()

    private var surfaceHolderGlueHost: SurfaceHolderGlueHost? = null
    private var hasSurface = false
    private var lastNotifiedPreparedState = false
    private var wasPlayingBeforeLastPause = false

    /** TsProbeで実測した総再生時間(ms)。未確定の間は-1(不明)。 */
    var knownDurationMs: Long = -1L
        private set

    /** 疑似シークで着地した論理再生位置(ms)。ExoPlayer自身の再生位置に加算する。 */
    var positionOffsetMs: Long = 0L
        private set

    /**
     * シーク確定直後〜補正プローブ完了までの間の表示位置。
     * 非nullの間は player.currentPosition を足さず、この値をそのまま表示に使う
     * (この間はまだ古いMediaSourceが再生中で、player.currentPositionは
     * 新しいpositionOffsetMsと組み合わせると値がずれるため)。
     */
    private var pendingSeekPositionMs: Long? = null

    fun setKnownDuration(durationMs: Long) {
        knownDurationMs = durationMs
        getCallback()?.onDurationChanged(this)
    }

    /**
     * シーク確定時に即座に呼び、補正プローブ(ネットワークI/O)の完了を待たず
     * シークバー表示だけ先に概算位置へ追従させる。これを呼ばないと、シーク確定の
     * 瞬間に通常ポーリングが再開して一瞬古い位置に巻き戻ってから正しい位置へ
     * 進むという不自然な動きになる。
     */
    fun notifySeekPending(estimatedPositionMs: Long) {
        pendingSeekPositionMs = estimatedPositionMs
        val callback = getCallback() ?: return
        callback.onCurrentPositionChanged(this)
        callback.onBufferedPositionChanged(this)
    }

    /** 疑似シーク(MediaSource再構築)が完了した直後に呼び、論理位置を確定させる。 */
    fun notifySeekApplied(newPositionOffsetMs: Long) {
        pendingSeekPositionMs = null
        positionOffsetMs = newPositionOffsetMs
        val callback = getCallback() ?: return
        callback.onCurrentPositionChanged(this)
        callback.onBufferedPositionChanged(this)
    }

    override fun onAttachedToHost(host: PlaybackGlueHost) {
        if (host is SurfaceHolderGlueHost) {
            surfaceHolderGlueHost = host
            host.setSurfaceHolderCallback(playerListener)
        }
        notifyStateChanged()
        player.addListener(playerListener)
    }

    override fun onDetachedFromHost() {
        player.removeListener(playerListener)
        surfaceHolderGlueHost?.setSurfaceHolderCallback(null)
        surfaceHolderGlueHost = null
        hasSurface = false
        val callback = getCallback() ?: return
        callback.onBufferingStateChanged(this, false)
        callback.onPlayStateChanged(this)
        maybeNotifyPreparedStateChanged(callback)
    }

    override fun setProgressUpdatingEnabled(enable: Boolean) {
        handler.removeCallbacks(this)
        if (enable) handler.post(this)
    }

    override fun isPlaying(): Boolean = !Util.shouldShowPlayButton(player)

    override fun getDuration(): Long = knownDurationMs

    override fun getCurrentPosition(): Long {
        pendingSeekPositionMs?.let { pending ->
            return if (knownDurationMs >= 0) pending.coerceIn(0, knownDurationMs) else pending
        }
        if (player.playbackState == Player.STATE_IDLE) return -1
        val position = positionOffsetMs + player.currentPosition
        return if (knownDurationMs >= 0) position.coerceIn(0, knownDurationMs) else position
    }

    override fun play() {
        if (Util.handlePlayButtonAction(player)) getCallback()?.onPlayStateChanged(this)
    }

    override fun pause() {
        wasPlayingBeforeLastPause = isPlaying()
        if (Util.handlePauseButtonAction(player)) getCallback()?.onPlayStateChanged(this)
    }

    /**
     * Leanbackはシーク開始時に無条件でpause()を呼ぶ(PlaybackTransportControlGlue内の
     * SeekUiClient.onSeekStarted、オーバーライド不可)。シークバーへの単なるフォーカス/移動を
     * 「シークが実行された」と誤解させる悪いアフォーダンスになるため、
     * (シーク開始直前に再生中だった場合のみ)即座に再開して再生を止めない。
     * 呼び出し元は TsSeekDataProvider.getSeekPositions() 参照。
     */
    fun resumePlaybackIfWasPlaying() {
        if (wasPlayingBeforeLastPause) play()
    }

    override fun seekTo(positionInMs: Long) {
        onSeekRequested(positionInMs)
    }

    override fun getBufferedPosition(): Long {
        pendingSeekPositionMs?.let { pending ->
            return if (knownDurationMs >= 0) pending.coerceIn(0, knownDurationMs) else pending
        }
        val position = positionOffsetMs + player.bufferedPosition
        return if (knownDurationMs >= 0) position.coerceIn(0, knownDurationMs) else position
    }

    override fun isPrepared(): Boolean =
        player.playbackState != Player.STATE_IDLE && (surfaceHolderGlueHost == null || hasSurface)

    override fun run() {
        val callback = getCallback() ?: return
        callback.onCurrentPositionChanged(this)
        callback.onBufferedPositionChanged(this)
        handler.postDelayed(this, updatePeriodMs.toLong())
    }

    private fun setVideoSurface(surface: Surface?) {
        hasSurface = surface != null
        player.setVideoSurface(surface)
        getCallback()?.let { maybeNotifyPreparedStateChanged(it) }
    }

    private fun notifyStateChanged() {
        val playbackState = player.playbackState
        val callback = getCallback() ?: return
        maybeNotifyPreparedStateChanged(callback)
        callback.onPlayStateChanged(this)
        callback.onBufferingStateChanged(this, playbackState == Player.STATE_BUFFERING)
        if (playbackState == Player.STATE_ENDED) callback.onPlayCompleted(this)
    }

    private fun maybeNotifyPreparedStateChanged(callback: PlayerAdapter.Callback) {
        val prepared = isPrepared()
        if (lastNotifiedPreparedState != prepared) {
            lastNotifiedPreparedState = prepared
            callback.onPreparedStateChanged(this)
        }
    }

    private inner class PlayerEventListener : Player.Listener, SurfaceHolder.Callback {

        override fun surfaceCreated(holder: SurfaceHolder) {
            setVideoSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // Do nothing.
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            setVideoSurface(null)
        }

        override fun onPlayerError(error: PlaybackException) {
            getCallback()?.onError(this@TsSeekPlayerAdapter, error.errorCode, error.message ?: "")
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val callback = getCallback() ?: return
            // duration は knownDurationMs 側で管理するため、ここでは position 系のみ再通知する。
            callback.onCurrentPositionChanged(this@TsSeekPlayerAdapter)
            callback.onBufferedPositionChanged(this@TsSeekPlayerAdapter)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val callback = getCallback() ?: return
            callback.onCurrentPositionChanged(this@TsSeekPlayerAdapter)
            callback.onBufferedPositionChanged(this@TsSeekPlayerAdapter)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                notifyStateChanged()
            }
        }
    }
}
