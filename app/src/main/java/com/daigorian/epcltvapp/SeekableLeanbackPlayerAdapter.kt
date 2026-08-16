package com.daigorian.epcltvapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import androidx.leanback.media.PlaybackGlueHost
import androidx.leanback.media.PlayerAdapter
import androidx.leanback.media.SurfaceHolderGlueHost
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer

/**
 * androidx.media3.ui.leanback.LeanbackPlayerAdapter は final のため継承できず、
 * Leanbackがシーク開始時に無条件で呼ぶ pause() を打ち消すフックを差し込めない
 * (TsSeekPlayerAdapter参照。あちらはTS固有のバイトオフセット計算のために自前実装が
 * 必要だったが、こちらはそれが理由の全て)。
 * duration/position/seekTo等の挙動は本家LeanbackPlayerAdapter(media3-ui-leanback 1.3.1)と
 * 同一のロジックをそのまま踏襲する。
 */
@UnstableApi
internal class SeekableLeanbackPlayerAdapter(
    private val context: Context,
    private val player: ExoPlayer,
    private val updatePeriodMs: Int,
    // シーク確定時のフック。Leanbackはシーク確定時に
    // PlaybackSupportFragment.setSeekMode(false) から showControlsOverlay() を呼ぶだけで
    // フェードタイマーを再開しない(leanback-1.0.0のstartFadeTimer()の呼び出し元は
    // tickle()/onResume()/setControlsOverlayAutoHideEnabled()の3つのみで、
    // showControlsOverlay()からは呼ばれない)。確定後にオーバーレイが残るのは
    // PlaybackVideoFragment.applyControlsAutoHide() が自動非表示を戻すまでの間だけだが、
    // シークしたのだから今すぐ映像に戻りたい場面でタイマーの分だけ待たせる理由がないため、
    // 呼び出し側で明示的に閉じる。
    private val onSeekApplied: () -> Unit = {},
) : PlayerAdapter(), Runnable {

    private val handler = Handler(Looper.getMainLooper())
    private val playerListener = PlayerEventListener()

    private var surfaceHolderGlueHost: SurfaceHolderGlueHost? = null
    private var hasSurface = false
    private var lastNotifiedPreparedState = false
    private var wasPlayingBeforeLastPause = false

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

    override fun getDuration(): Long {
        val durationMs = player.duration
        return if (durationMs == C.TIME_UNSET) -1 else durationMs
    }

    override fun getCurrentPosition(): Long =
        if (player.playbackState == Player.STATE_IDLE) -1 else player.currentPosition

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
     * 呼び出し元は DurationSeekDataProvider.getSeekPositions() 参照。
     */
    fun resumePlaybackIfWasPlaying() {
        if (wasPlayingBeforeLastPause) play()
    }

    override fun seekTo(positionInMs: Long) {
        player.seekTo(player.currentMediaItemIndex, positionInMs)
        onSeekApplied()
    }

    override fun getBufferedPosition(): Long = player.bufferedPosition

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
            getCallback()?.onError(
                this@SeekableLeanbackPlayerAdapter,
                error.errorCode,
                context.getString(androidx.leanback.R.string.lb_media_player_error, error.errorCode, 0)
            )
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val callback = getCallback() ?: return
            callback.onDurationChanged(this@SeekableLeanbackPlayerAdapter)
            callback.onCurrentPositionChanged(this@SeekableLeanbackPlayerAdapter)
            callback.onBufferedPositionChanged(this@SeekableLeanbackPlayerAdapter)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val callback = getCallback() ?: return
            callback.onCurrentPositionChanged(this@SeekableLeanbackPlayerAdapter)
            callback.onBufferedPositionChanged(this@SeekableLeanbackPlayerAdapter)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width == 0 || videoSize.height == 0) return
            val scaledWidth = Math.round(videoSize.width * videoSize.pixelWidthHeightRatio)
            getCallback()?.onVideoSizeChanged(this@SeekableLeanbackPlayerAdapter, scaledWidth, videoSize.height)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                notifyStateChanged()
            }
        }
    }
}
