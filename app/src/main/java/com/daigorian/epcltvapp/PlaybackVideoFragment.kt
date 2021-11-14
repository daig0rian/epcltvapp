package com.daigorian.epcltvapp

import android.net.Uri
import android.os.Bundle
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.PlaybackControlsRow
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem

/** Handles video playback with media controls. */
class PlaybackVideoFragment : VideoSupportFragment() {

    private lateinit var mTransportControlGlue: PlaybackTransportControlGlue<VlcPlayerAdapter>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val recordedProgram =
            activity?.intent?.getSerializableExtra(DetailsActivity.RECORDEDPROGRAM) as RecordedProgram?
        val recordedItem =
            activity?.intent?.getSerializableExtra(DetailsActivity.RECORDEDITEM) as RecordedItem?

        val actionId =
            activity?.intent?.getSerializableExtra(DetailsActivity.ACTIONID) as Long

        val glueHost = VideoSupportFragmentGlueHost(this@PlaybackVideoFragment)
        val playerAdapter = VlcPlayerAdapter(requireContext())
        playerAdapter.setRepeatAction(PlaybackControlsRow.RepeatAction.INDEX_NONE)

        mTransportControlGlue = PlaybackTransportControlGlue(activity, playerAdapter)
        mTransportControlGlue.host = glueHost
        mTransportControlGlue.title = recordedProgram?.name ?: recordedItem?.name
        mTransportControlGlue.subtitle = recordedProgram?.description ?: recordedItem?.description
        mTransportControlGlue.isSeekEnabled = true   // TODO: VlcPlayerAdapter has property for seek capability
        mTransportControlGlue.playWhenPrepared()

        val movieUrl = if(recordedProgram != null){
            // EPGStation V1.x.x　
            if (actionId == VideoDetailsFragment.ACTION_WATCH_ORIGINAL_TS) {
                EpgStation.getTsVideoURL(recordedProgram.id.toString())
            } else {
                EpgStation.getEncodedVideoURL(recordedProgram.id.toString(),actionId.toString())
            }
        }else{
            // EPGStation V2.x.x　
            EpgStationV2.getVideoURL(actionId.toString())
        }

        playerAdapter.setDataSource(Uri.parse(movieUrl))
    }

    override fun onPause() {
        super.onPause()
        mTransportControlGlue.pause()
    }
}