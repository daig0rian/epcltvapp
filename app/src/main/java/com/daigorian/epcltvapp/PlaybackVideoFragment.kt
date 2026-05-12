package com.daigorian.epcltvapp

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.leanback.app.PlaybackSupportFragment
import androidx.leanback.app.PlaybackSupportFragmentGlueHost
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.media.PlayerAdapter
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.PlaybackControlsRow
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.ApiErrorV2
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.HlsStream
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import org.videolan.libvlc.util.VLCVideoLayout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/** Handles video playback with media controls. */
class PlaybackVideoFragment : PlaybackSupportFragment() {

    var mVLCVideoLayout: VLCVideoLayout? = null

    private lateinit var mTransportControlGlue: MyPlaybackTransportControlGlue<VlcPlayerAdapter>

    private var hlsStreamId: Int? = null
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private val hlsRetryRunnable = Runnable {
        val adapter = mTransportControlGlue.playerAdapter
        if (!adapter.isPlaying()) {
            Log.d(TAG, "HLS retry: VLC not playing, calling retryPlay()")
            adapter.retryPlay()
        } else {
            Log.d(TAG, "HLS retry: VLC already playing, skip")
        }
    }
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            hlsStreamId?.let { id ->
                EpgStationV2.api?.keepStream(id)?.enqueue(object : Callback<ApiErrorV2> {
                    override fun onResponse(call: Call<ApiErrorV2>, response: Response<ApiErrorV2>) {
                        Log.d(TAG, "HLS keep-alive sent for streamId=$id")
                    }
                    override fun onFailure(call: Call<ApiErrorV2>, t: Throwable) {
                        Log.w(TAG, "HLS keep-alive failed for streamId=$id")
                    }
                })
            }
            keepAliveHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val recordedProgram = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity?.intent?.getSerializableExtra(DetailsActivity.RECORDEDPROGRAM, RecordedProgram::class.java)
        } else {
            @Suppress("DEPRECATION")
            activity?.intent?.getSerializableExtra(DetailsActivity.RECORDEDPROGRAM) as RecordedProgram?
        }
        val recordedItem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity?.intent?.getSerializableExtra(DetailsActivity.RECORDEDITEM, RecordedItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            activity?.intent?.getSerializableExtra(DetailsActivity.RECORDEDITEM) as RecordedItem?
        }
        val actionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity?.intent?.getSerializableExtra(DetailsActivity.ACTIONID, java.lang.Long::class.java)?.toLong() ?: 0L
        } else {
            @Suppress("DEPRECATION")
            activity?.intent?.getSerializableExtra(DetailsActivity.ACTIONID) as? Long ?: 0L
        }

        val glueHost = PlaybackSupportFragmentGlueHost(this@PlaybackVideoFragment)
        val playerAdapter = VlcPlayerAdapter(requireContext())
        playerAdapter.setRepeatAction(PlaybackControlsRow.RepeatAction.INDEX_NONE)

        mTransportControlGlue = MyPlaybackTransportControlGlue(activity, playerAdapter)
        mTransportControlGlue.host = glueHost
        mTransportControlGlue.title = recordedProgram?.name ?: recordedItem?.name
        mTransportControlGlue.subtitle = recordedProgram?.description ?: recordedItem?.description
        mTransportControlGlue.isSeekEnabled = true   // TODO: VlcPlayerAdapter has property for seek capability
        mTransportControlGlue.playWhenPrepared()

        val isHls = activity?.intent?.getBooleanExtra(DetailsActivity.IS_HLS, false) ?: false

        if (isHls && recordedItem != null) {
            // 追いかけ再生: HLS ストリームを開始してから M3U8 URL をセット
            EpgStationV2.api?.startRecordedHlsStream(actionId)?.enqueue(object : Callback<HlsStream> {
                override fun onResponse(call: Call<HlsStream>, response: Response<HlsStream>) {
                    val streamId = response.body()?.streamId
                    if (streamId == null) {
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), getString(R.string.hls_stream_start_failed), Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                    hlsStreamId = streamId
                    Log.d(TAG, "HLS stream started: streamId=$streamId")
                    val m3u8Url = EpgStationV2.getHlsStreamUrl(streamId)
                    activity?.runOnUiThread {
                        Log.d(TAG, "Calling setDataSource: $m3u8Url")
                        playerAdapter.setDataSource(Uri.parse(m3u8Url))
                        Log.d(TAG, "Calling playerAdapter.play() directly, isPrepared=${playerAdapter.isPrepared()}")
                        mTransportControlGlue.playerAdapter.play()
                        keepAliveHandler.post(keepAliveRunnable)
                        // If M3U8 wasn't ready yet, retry after segments are generated
                        keepAliveHandler.postDelayed(hlsRetryRunnable, HLS_RETRY_DELAY_MS)
                    }
                }
                override fun onFailure(call: Call<HlsStream>, t: Throwable) {
                    Log.e(TAG, "HLS stream start failed", t)
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), getString(R.string.hls_stream_start_failed), Toast.LENGTH_LONG).show()
                    }
                }
            })
        } else {
            val movieUrl = if (recordedProgram != null) {
                // EPGStation V1.x.x
                if (actionId == VideoDetailsFragment.ACTION_WATCH_ORIGINAL_TS) {
                    EpgStation.getTsVideoURL(recordedProgram.id.toString())
                } else {
                    EpgStation.getEncodedVideoURL(recordedProgram.id.toString(), actionId.toString())
                }
            } else {
                // EPGStation V2.x.x
                EpgStationV2.getVideoURL(actionId.toString())
            }
            playerAdapter.setDataSource(Uri.parse(movieUrl))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = super.onCreateView(inflater, container, savedInstanceState) as ViewGroup?
        mVLCVideoLayout = LayoutInflater.from(context).inflate(
            R.layout.video_layout, root, false
        ) as VLCVideoLayout
        root?.addView(mVLCVideoLayout, 0)
        if (mTransportControlGlue.playerAdapter is VlcPlayerAdapter){
            mTransportControlGlue.playerAdapter.setVLCVideoLayout(mVLCVideoLayout)
        }

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // HLS ストリームのクリーンアップ
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        keepAliveHandler.removeCallbacks(hlsRetryRunnable)
        hlsStreamId?.let { id ->
            EpgStationV2.api?.stopStream(id)?.enqueue(object : Callback<ApiErrorV2> {
                override fun onResponse(call: Call<ApiErrorV2>, response: Response<ApiErrorV2>) {
                    Log.d(TAG, "HLS stream stopped: streamId=$id")
                }
                override fun onFailure(call: Call<ApiErrorV2>, t: Throwable) {
                    Log.w(TAG, "HLS stream stop failed: streamId=$id")
                }
            })
            hlsStreamId = null
        }
        mVLCVideoLayout = null
        if (mTransportControlGlue.playerAdapter is VlcPlayerAdapter){
            mTransportControlGlue.playerAdapter.setVLCVideoLayout(mVLCVideoLayout)
        }
    }

    override fun onPause() {
        super.onPause()
        mTransportControlGlue.pause()
    }

    companion object {
        private const val TAG = "PlaybackVideoFragment"
        private const val KEEP_ALIVE_INTERVAL_MS = 10_000L
        private const val HLS_RETRY_DELAY_MS = 15_000L
    }

    class MyPlaybackTransportControlGlue<T: PlayerAdapter>(context:Context? , impl:T )
        : PlaybackTransportControlGlue<T>(context , impl)
    {
        private val ccAction = PlaybackControlsRow.ClosedCaptioningAction(getContext())

        override fun onCreatePrimaryActions(primaryActionsAdapter: ArrayObjectAdapter) {
            super.onCreatePrimaryActions(primaryActionsAdapter)
            primaryActionsAdapter.apply {
                add(ccAction)
            }
        }

        override fun onActionClicked(action: Action?) {
            when(action) {
                ccAction -> {
                    // Handle ClosedCaptioningAction
                    val pa = playerAdapter
                    if (pa is VlcPlayerAdapter){
                        pa.toggleClosedCaptioning()
                    }
                }
                else ->
                    // The superclass handles play/pause and delegates next/previous actions to abstract methods,
                    // so those two methods should be overridden rather than handling the actions here.
                    super.onActionClicked(action)
            }
        }

    }
}