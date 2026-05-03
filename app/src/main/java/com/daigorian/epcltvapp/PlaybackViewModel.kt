package com.daigorian.epcltvapp

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.roundToLong

class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    private val libVLC = LibVLC(application, arrayListOf("--verbose=3"))
    private val vlcPlayer = MediaPlayer(libVLC)

    private val _isPlaying     = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering   = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _duration      = MutableStateFlow(-1L)
    val duration: StateFlow<Long> = _duration

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _isCompleted   = MutableStateFlow(false)
    val isCompleted: StateFlow<Boolean> = _isCompleted

    // TS コンテンツは libVLC が尺を 0 と報告するため再生位置から概算する
    private var estimatedDuration = -1L

    // 字幕トラックのインデックス (-1 = OFF)
    private var subtitleTrackIndex = -1

    fun effectiveDuration(): Long = if (_duration.value > 0) _duration.value else estimatedDuration

    fun setMedia(uri: Uri) {
        reset()
        val media = Media(libVLC, uri)
        vlcPlayer.media = media
        vlcPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    Log.d(TAG, "Opening")
                    _isBuffering.value = true
                }
                MediaPlayer.Event.Playing -> {
                    Log.d(TAG, "Playing")
                    _isPlaying.value = true
                    _isBuffering.value = false
                }
                MediaPlayer.Event.Paused -> {
                    Log.d(TAG, "Paused")
                    _isPlaying.value = false
                }
                MediaPlayer.Event.Stopped -> {
                    Log.d(TAG, "Stopped")
                    _isPlaying.value = false
                }
                MediaPlayer.Event.EndReached -> {
                    Log.d(TAG, "EndReached")
                    _isPlaying.value = false
                    _isCompleted.value = true
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "EncounteredError")
                }
                MediaPlayer.Event.TimeChanged -> {
                    _currentPosition.value = event.timeChanged
                }
                MediaPlayer.Event.PositionChanged -> {
                    val pos = event.positionChanged
                    if (pos > 0f) {
                        estimatedDuration = (_currentPosition.value / pos).roundToLong()
                    }
                    if (_duration.value <= 0) {
                        // TS 尺概算値が更新されたことを下流に伝えるため再発行
                        _currentPosition.value = _currentPosition.value
                    }
                }
                MediaPlayer.Event.LengthChanged -> {
                    Log.d(TAG, "LengthChanged: ${event.lengthChanged}")
                    _duration.value = event.lengthChanged
                }
                MediaPlayer.Event.Vout -> {
                    Log.d(TAG, "Vout")
                    _isBuffering.value = false
                }
                else -> {}
            }
        }
    }

    /** VLCVideoLayout をアタッチし再生を開始する */
    fun attachVideoLayout(layout: VLCVideoLayout) {
        Log.d(TAG, "attachVideoLayout")
        vlcPlayer.attachViews(layout, null, true, false)
        vlcPlayer.play()
    }

    fun detachVideoLayout() {
        Log.d(TAG, "detachVideoLayout")
        vlcPlayer.detachViews()
    }

    fun pause() { if (vlcPlayer.isPlaying) vlcPlayer.pause() }

    fun togglePlayPause() {
        if (vlcPlayer.isPlaying) vlcPlayer.pause() else vlcPlayer.play()
    }

    fun fastForward(ms: Long = 10_000L) {
        val effective = effectiveDuration()
        val target = (_currentPosition.value + ms).let { if (effective > 0) it.coerceAtMost(effective) else it }
        seekTo(target)
    }

    fun rewind(ms: Long = 10_000L) {
        seekTo((_currentPosition.value - ms).coerceAtLeast(0L))
    }

    fun seekTo(positionMs: Long) {
        if (_duration.value > 0) {
            vlcPlayer.setTime(positionMs, false)
        } else if (estimatedDuration > 0) {
            vlcPlayer.setPosition(positionMs / estimatedDuration.toFloat(), false)
        }
    }

    fun toggleSubtitles() {
        val context = getApplication<Application>()
        val tracks = vlcPlayer.getTracks(IMedia.Track.Type.Text)
        if (tracks.isNullOrEmpty()) {
            Toast.makeText(context, context.getString(R.string.no_subtitle), Toast.LENGTH_SHORT).show()
            return
        }
        if (subtitleTrackIndex < 0) {
            subtitleTrackIndex = 0
            vlcPlayer.selectTrack(tracks[0].id)
            Toast.makeText(context, tracks[0].name, Toast.LENGTH_SHORT).show()
        } else if (subtitleTrackIndex >= tracks.size - 1) {
            subtitleTrackIndex = -1
            vlcPlayer.unselectTrackType(IMedia.Track.Type.Text)
            Toast.makeText(context, context.getString(R.string.subtitle_off), Toast.LENGTH_SHORT).show()
        } else {
            subtitleTrackIndex++
            vlcPlayer.selectTrack(tracks[subtitleTrackIndex].id)
            Toast.makeText(context, tracks[subtitleTrackIndex].name, Toast.LENGTH_SHORT).show()
        }
    }

    private fun reset() {
        vlcPlayer.stop()
        if (vlcPlayer.hasMedia()) {
            vlcPlayer.media?.release()
            vlcPlayer.media = null
        }
        _isPlaying.value      = false
        _isBuffering.value    = false
        _duration.value       = -1L
        _currentPosition.value = 0L
        _isCompleted.value    = false
        estimatedDuration     = -1L
        subtitleTrackIndex    = -1
    }

    override fun onCleared() {
        super.onCleared()
        reset()
        vlcPlayer.release()
        libVLC.release()
    }

    companion object {
        private const val TAG = "PlaybackViewModel"
    }
}
