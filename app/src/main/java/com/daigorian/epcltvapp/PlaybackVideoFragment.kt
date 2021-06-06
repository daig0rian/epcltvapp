package com.daigorian.epcltvapp

import android.net.Uri
import android.os.Bundle
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.MediaPlayerAdapter
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.PlaybackControlsRow

/** Handles video playback with media controls. */
class PlaybackVideoFragment : VideoSupportFragment() {

    private lateinit var mTransportControlGlue: PlaybackTransportControlGlue<MediaPlayerAdapter>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val recordedProgram =
            activity?.intent?.getSerializableExtra(DetailsActivity.RECORDEDPROGRAM) as RecordedProgram
        val actionId =
            activity?.intent?.getSerializableExtra(DetailsActivity.ACTIONID) as Long

        val glueHost = VideoSupportFragmentGlueHost(this@PlaybackVideoFragment)
        val playerAdapter = MediaPlayerAdapter(context)
        playerAdapter.setRepeatAction(PlaybackControlsRow.RepeatAction.INDEX_NONE)

        mTransportControlGlue = PlaybackTransportControlGlue(activity, playerAdapter)
        mTransportControlGlue.host = glueHost
        mTransportControlGlue.title = recordedProgram.name
        mTransportControlGlue.subtitle = recordedProgram.description
        mTransportControlGlue.playWhenPrepared()

        val movieUrl = if (actionId == VideoDetailsFragment.ACTION_WATCH_ORIGINAL_TS) {
            EpgStation.getTsVideoURL(recordedProgram.id.toString())
        } else {
            EpgStation.getEncodedVideoURL(recordedProgram.id.toString(),actionId.toString())
        }

        playerAdapter.setDataSource(Uri.parse(movieUrl))
    }

    override fun onPause() {
        super.onPause()
        mTransportControlGlue.pause()
    }
}