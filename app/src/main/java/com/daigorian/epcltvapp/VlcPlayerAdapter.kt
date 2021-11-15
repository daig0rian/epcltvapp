package com.daigorian.epcltvapp

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.leanback.media.PlaybackBaseControlGlue
import androidx.leanback.media.PlaybackGlueHost
import androidx.leanback.media.PlayerAdapter
import androidx.leanback.media.SurfaceHolderGlueHost
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.MediaPlayer.*
import java.io.IOException
import java.util.*
import kotlin.math.roundToLong

/**
 * This implementation extends the [PlayerAdapter] with a [org.videolan.libvlc.MediaPlayer].
 */
class VlcPlayerAdapter(var mContext: Context) : PlayerAdapter() {
    /**
     * Return the VlcPlayer associated with the VlcPlayerAdapter. App can use the instance
     * to config DRM or control volumes, etc.
     *
     * @return The VlcPlayer associated with the VlcPlayerAdapter.
     */

    // For LibVLC
    private val args: ArrayList<String?> = arrayListOf("--verbose=0")
    private val mLibVLC: LibVLC = LibVLC(mContext, args)
    private val vlcPlayer = MediaPlayer(mLibVLC)

    var mSurfaceHolderGlueHost: SurfaceHolderGlueHost? = null

    var mInitialized = false // true when the VlcPlayer is prepared/initialized
    var mMediaSourceUri: Uri? = null
    var mHasDisplay = false
    var mBufferedProgress: Long = 0


    var mDuration = -1L
    var mEstimatedDuration = -1L
    var mTime = -1L
    var mSeekable = false
    var mPauseable = false

    override fun onAttachedToHost(host: PlaybackGlueHost) {
        Log.d(TAG, "onAttachedToHost()")
        if (host is SurfaceHolderGlueHost) {
            mSurfaceHolderGlueHost = host
            mSurfaceHolderGlueHost!!.setSurfaceHolderCallback(this.VideoPlayerSurfaceHolderCallback())
        }
    }

    /**
     * Will reset the [org.videolan.libvlc.MediaPlayer] and the glue such that a new file can be played. You are
     * not required to call this method before playing the first file. However you have to call it
     * before playing a second one.
     */
    fun reset() {
        Log.d(TAG, "reset()")
        changeToUninitialized()
        vlcPlayer.stop()
        if(vlcPlayer.hasMedia()){
            vlcPlayer.media?.release()
            vlcPlayer.media = null
        }
    }

    fun changeToUninitialized() {
        Log.d(TAG, "changeToUninitialized()")
        if(vlcPlayer.hasMedia()){
            vlcPlayer.media?.release()
            vlcPlayer.media = null
        }
        if (mInitialized) {
            mInitialized = false
            if (mHasDisplay) {
                callback.onPreparedStateChanged(this@VlcPlayerAdapter)
            }
        }
    }

    /**
     * Release internal VlcPlayer. Should not use the object after call release().
     */
    fun release() {
        Log.d(TAG, "release()")
        changeToUninitialized()
        mHasDisplay = false
        vlcPlayer.release()
        mLibVLC.release()
    }

    override fun onDetachedFromHost() {
        Log.d(TAG, "onDetachedFromHost()")
        if (mSurfaceHolderGlueHost != null) {
            mSurfaceHolderGlueHost!!.setSurfaceHolderCallback(null)
            mSurfaceHolderGlueHost = null
        }
        reset()
        release()
    }


    fun setDisplay(surfaceHolder: SurfaceHolder?) {
        Log.d(TAG, "setDisplay()")
        val hadDisplay = mHasDisplay
        mHasDisplay = surfaceHolder != null
        if (hadDisplay == mHasDisplay) {
            return
        }
        if (surfaceHolder != null){
            vlcPlayer.vlcVout.setVideoSurface(surfaceHolder.surface,surfaceHolder)
            vlcPlayer.vlcVout.setWindowSize(surfaceHolder.surfaceFrame.width(),surfaceHolder.surfaceFrame.height())
            vlcPlayer.vlcVout.attachViews()
            mInitialized = true
        }else{
            mInitialized = false
            vlcPlayer.vlcVout.detachViews()
        }

        callback.onPreparedStateChanged(this@VlcPlayerAdapter)
    }




    override fun isPlaying(): Boolean {
        Log.d(TAG, "isPlaying()")
        return vlcPlayer.isPlaying
    }

    override fun getDuration(): Long {
        // Log.d(TAG, "getDuration()")
        if(mDuration > 0){
            return mDuration
        }else{
            return mEstimatedDuration
        }

    }

    override fun getCurrentPosition(): Long {
        //Log.d(TAG, "getCurrentPosition()")
        return mTime
    }

    override fun play() {
        Log.d(TAG, "play()")
        if ( vlcPlayer.isPlaying) {
            return
        }
        vlcPlayer.play()
    }

    override fun pause() {
        Log.d(TAG, "pause()")
        if (isPlaying) {
            vlcPlayer.pause()
        }
    }

    fun toggleClosedCaptioning(){
        Log.d(TAG, "toggleClosedCaptioning()")
        val currentSubtitleTrackId = vlcPlayer.spuTrack
        Log.d(TAG, "  currentSubtitleTrack : $currentSubtitleTrackId")
        Log.d(TAG, "  spuTracksCount : "+ vlcPlayer.spuTracksCount)
        for ( spuTrack in vlcPlayer.spuTracks){
            Log.d(TAG, "  id : " + spuTrack.id +"  name : " + spuTrack.name)
        }

        var currentSubtitleTrackIndex = -1
        for ( i in vlcPlayer.spuTracks.indices){
            if (vlcPlayer.spuTracks[i].id == currentSubtitleTrackId){
                currentSubtitleTrackIndex = i
            }
        }
        Log.d(TAG, "  currentSubtitleTrackIndex : $currentSubtitleTrackIndex")

        var nextSubtitleTrackIndex = currentSubtitleTrackIndex + 1
        if (vlcPlayer.spuTracksCount == nextSubtitleTrackIndex){
            nextSubtitleTrackIndex = 0
        }
        Log.d(TAG, "  nextSubtitleTrackIndex : $nextSubtitleTrackIndex")
        Log.d(TAG, "  nextSubtitleTrackId : " + vlcPlayer.spuTracks[nextSubtitleTrackIndex].id)
        Log.d(TAG, "  nextSubtitleTrackName : "+ vlcPlayer.spuTracks[nextSubtitleTrackIndex].name)

        vlcPlayer.spuTrack = vlcPlayer.spuTracks[nextSubtitleTrackIndex].id
        Toast.makeText( mContext,vlcPlayer.spuTracks[nextSubtitleTrackIndex].name,Toast.LENGTH_SHORT).show()

    }

    override fun seekTo(newPosition: Long) {
        Log.d(TAG, "seekTo( "+newPosition+" )")
        if (!mInitialized) {
            return
        }
        vlcPlayer.setTime(newPosition,true)
    }

    override fun getBufferedPosition(): Long {
        Log.d(TAG, "getBufferedPosition()")
        return mBufferedProgress
    }

    override fun getSupportedActions(): Long {
        Log.d(TAG, "getSupportedActions()")

        return (PlaybackBaseControlGlue.ACTION_PLAY_PAUSE
                + PlaybackBaseControlGlue.ACTION_REWIND
                + PlaybackBaseControlGlue.ACTION_FAST_FORWARD).toLong()
    }

    /**
     * Sets the media source of the player with a given URI.
     *
     * @return Returns `true` if uri represents a new media; `false`
     * otherwise.
     */
    fun setDataSource(uri: Uri?): Boolean {
        Log.d(TAG, "setDataSource()")
        if (if (mMediaSourceUri != null) mMediaSourceUri == uri else uri == null) {
            return false
        }
        mMediaSourceUri = uri
        prepareMediaForPlaying()
        return true
    }

    private fun prepareMediaForPlaying() {
        Log.d(TAG, "  prepareMediaForPlaying()")
        reset()

        try {
            val media = Media( mLibVLC, mMediaSourceUri )
            vlcPlayer.media = media
        } catch (e: IOException) {
            throw java.lang.RuntimeException("Invalid asset folder")
        }

        vlcPlayer.setEventListener { event ->
            when (event.type) {
                Event.MediaChanged -> {
                    Log.d(TAG, "libvlc Event.MediaChanged:")
                }
                Event.Opening -> {
                    Log.d(TAG, "libvlc Event.Opening")
                    callback.onBufferingStateChanged(this@VlcPlayerAdapter, true)

                    if (mSurfaceHolderGlueHost == null || mHasDisplay) {
                        callback.onPreparedStateChanged(this@VlcPlayerAdapter)
                    }
                }
                Event.Buffering -> {
                    Log.d(TAG, "libvlc Event.Buffering" + event.buffering)
                    mBufferedProgress = (duration * event.buffering / 100).roundToLong()
                    callback.onBufferedPositionChanged(this@VlcPlayerAdapter)
                }
                Event.Playing -> {
                    Log.d(TAG, "libvlc Event.Playing")
                    callback.onPlayStateChanged(this@VlcPlayerAdapter)
                }
                Event.Paused -> {
                    Log.d(TAG, "libvlc Event.Paused")
                    callback.onPlayStateChanged(this@VlcPlayerAdapter)
                }
                Event.Stopped -> {
                    // Playback of a media list player has stopped
                    Log.d(TAG, "libvlc Event.Stopped")
                    callback.onPlayStateChanged(this@VlcPlayerAdapter)
                }
                Event.EndReached -> {
                    // A media list has reached the end.
                    Log.d(TAG, "libvlc Event.EndReached")
                    callback.onPlayStateChanged(this@VlcPlayerAdapter)
                    callback.onPlayCompleted(this@VlcPlayerAdapter)
                }
                Event.EncounteredError -> {
                    Log.d(TAG, "libvlc Event.EncounteredError")

                    callback.onError(
                        this@VlcPlayerAdapter, 0,
                        "an error occurred"
                    )
                }
                Event.TimeChanged -> {
                    //Log.d(TAG, "libvlc Event.TimeChanged:"+ event.timeChanged)
                    mTime = event.timeChanged
                    callback.onCurrentPositionChanged(this@VlcPlayerAdapter)
                }
                Event.PositionChanged -> {
                    //Log.d(TAG, "libvlc Event.PositionChanged:"+ event.positionChanged )
                    //VLCはTS動画の長さをを0と報告する。再生時間と再生位置から概算の長さを計算する
                    mEstimatedDuration =  (mTime / event.positionChanged).roundToLong()
                    //もし動画の長さが0以下であったら、概算の長さを取りに来るようにonDurationChangedを投げる。
                    if (mDuration <= 0){
                        callback.onDurationChanged(this@VlcPlayerAdapter)
                    }
                }
                Event.SeekableChanged -> {
                    Log.d(TAG, "libvlc Event.SeekableChanged to : " + event.seekable)
                    mSeekable = event.seekable
                    callback.onMetadataChanged(this@VlcPlayerAdapter)
                }
                Event.PausableChanged -> {
                    Log.d(TAG, "libvlc Event.PausableChanged to : "+ event.pausable)
                    mPauseable = event.pausable
                }
                Event.LengthChanged -> {
                    Log.d(TAG, "libvlc Event.LengthChanged to : " + event.lengthChanged)
                    mDuration = event.lengthChanged
                    callback.onDurationChanged(this@VlcPlayerAdapter)
                }
                Event.Vout -> {
                    Log.d(TAG, "libvlc Event.Vout. Vout count: " + event.voutCount)
                    callback.onBufferingStateChanged(this@VlcPlayerAdapter, false)

                }
                Event.ESAdded -> {
                    // A track was added
                    Log.d(TAG, "libvlc Event.ESAdded. ID: " + event.esChangedID + ", Type: " + event.esChangedType)
                }
                Event.ESDeleted -> {
                    // A track was removed
                    Log.d(TAG, "libvlc Event.ESDeleted. ID: " + event.esChangedID + ", Type: " + event.esChangedType)
                }
                Event.ESSelected -> {
                    // Tracks were selected or unselected
                    Log.d(TAG, "libvlc Event.ESSelected. ID: " + event.esChangedID + ", Type: " + event.esChangedType)
                }
                Event.RecordChanged -> {
                    Log.d(TAG, "libvlc Event.RecordChanged to : " + event.recording+ " " +event.recordPath)
                }
                else -> {
                    Log.d(TAG, "libvlc Event.Unknown")
                }


            }

        }
    }

    /**
     * @return True if VlcPlayer OnPreparedListener is invoked and got a SurfaceHolder if
     * [PlaybackGlueHost] provides SurfaceHolder.
     */
    override fun isPrepared(): Boolean {
        // Log.d(TAG, "isPrepared() : "+ (mInitialized && (mSurfaceHolderGlueHost == null || mHasDisplay)) )
        return mInitialized && (mSurfaceHolderGlueHost == null || mHasDisplay)
    }

    /**
     * Implements [SurfaceHolder.Callback] that can then be set on the
     * [PlaybackGlueHost].
     */
    internal inner class VideoPlayerSurfaceHolderCallback : SurfaceHolder.Callback {
        override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
            Log.d(TAG, "VideoPlayerSurfaceHolderCallback.surfaceCreated()")
            setDisplay(surfaceHolder)
        }

        override fun surfaceChanged(surfaceHolder: SurfaceHolder, i: Int, i1: Int, i2: Int) {
            Log.d(TAG, "VideoPlayerSurfaceHolderCallback.surfaceChanged()")
        }
        override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
            Log.d(TAG, "VideoPlayerSurfaceHolderCallback.surfaceDestroyed()")
            setDisplay(null)
        }
    }

    companion object {
        private const val TAG = "VlcPlayerAdapter"

    }

}