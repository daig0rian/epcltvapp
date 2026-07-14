package com.daigorian.epcltvapp

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.media.PlayerAdapter
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.PlaybackControlsRow
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import androidx.preference.PreferenceManager
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.ApiErrorV2
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.HlsStream
import com.daigorian.epcltvapp.epgstationv2caller.ManualReserveOption
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import com.daigorian.epcltvapp.epgstationv2caller.Schedule
import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.URL
import java.util.concurrent.TimeUnit

@UnstableApi
class PlaybackVideoFragment : VideoSupportFragment() {

    private lateinit var mTransportControlGlue: MyPlaybackTransportControlGlue
    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var overlayView: SubtitleOverlayView? = null

    // ARIB caption handles
    private var captionHandle: Long = 0
    private var superimposeHandle: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // Persisted toggle states
    private var captionEnabled = false
    private var superimposeEnabled = false
    private var preferSubAudio = false

    // Audio track state
    private val audioGroups = mutableListOf<Tracks.Group>()
    private var hasSubAudio = false

    // Content type
    private var isTsContent = false

    // Live viewing state
    private var liveChannelId: Long = -1L
    private var isLiveMpegTs = false

    // HLS state
    private var hlsStreamId: Int? = null
    private val keepAliveHandler = Handler(Looper.getMainLooper())
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

        val isHls = activity?.intent?.getBooleanExtra(DetailsActivity.IS_HLS, false) ?: false
        val isLive = activity?.intent?.getBooleanExtra(DetailsActivity.IS_LIVE, false) ?: false
        // 実験的機能: mpegts直送ライブ再生（チャンネルカード長押しから起動）
        isLiveMpegTs = activity?.intent?.getBooleanExtra(DetailsActivity.IS_LIVE_MPEGTS, false) ?: false
        // 切り分け中: mpegts直送はいったんネイティブTS処理(tsreadex/ARIB字幕)を通さず、
        // ExoPlayer標準の仕組みだけで再生できるか確認する。isTsContentには含めない。
        isTsContent = activity?.intent?.getBooleanExtra(DetailsActivity.IS_TS_CONTENT, false) ?: false
        val isAnyLive = isLive || isLiveMpegTs
        liveChannelId = activity?.intent?.getLongExtra(DetailsActivity.CHANNEL_ID, -1L) ?: -1L
        val liveChannelName = activity?.intent?.getStringExtra(DetailsActivity.CHANNEL_NAME)

        // Restore persisted states
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        captionEnabled = prefs.getBoolean(PREF_CAPTION_ENABLED, false)
        superimposeEnabled = prefs.getBoolean(PREF_SUPERIMPOSE_ENABLED, true)
        preferSubAudio = prefs.getBoolean(PREF_SUB_AUDIO, false)

        // Build ExoPlayer
        trackSelector = DefaultTrackSelector(requireContext())
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 8_000, 500, 1_000)
            .build()

        exoPlayer = ExoPlayer.Builder(requireContext(), DefaultRenderersFactory(requireContext()))
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build()

        exoPlayer!!.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "onPlaybackStateChanged: $playbackState")
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "onPlayerError: $error")
            }
            override fun onTracksChanged(tracks: Tracks) {
                val newAudioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                Log.d(TAG, "onTracksChanged: ${newAudioGroups.size} audio group(s)")
                audioGroups.clear()
                audioGroups.addAll(newAudioGroups)
                val hadSubAudio = hasSubAudio
                hasSubAudio = newAudioGroups.size >= 2
                if (hasSubAudio != hadSubAudio) {
                    mTransportControlGlue.updateAudioActionState(hasSubAudio)
                }
                if (hasSubAudio && preferSubAudio) {
                    selectAudioTrack(1)
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ch = captionHandle
                    if (ch != 0L) AribCaptionFilter.setFrameSize(ch, videoSize.width, videoSize.height)
                    val sh = superimposeHandle
                    if (sh != 0L) AribCaptionFilter.setFrameSize(sh, videoSize.width, videoSize.height)
                    overlayView?.setVideoSize(videoSize.width, videoSize.height)
                    Log.d(TAG, "videoSize=${videoSize.width}x${videoSize.height}")
                }
            }
        })

        // Leanback glue
        val playerAdapter = LeanbackPlayerAdapter(requireContext(), exoPlayer!!, UPDATE_PERIOD_MS)
        val glueHost = VideoSupportFragmentGlueHost(this@PlaybackVideoFragment)

        mTransportControlGlue = MyPlaybackTransportControlGlue(
            activity, playerAdapter, isTsContent, isAnyLive, captionEnabled, superimposeEnabled, preferSubAudio, hasSubAudio
        )
        mTransportControlGlue.host = glueHost
        mTransportControlGlue.title = recordedProgram?.name ?: recordedItem?.name ?: liveChannelName
        mTransportControlGlue.subtitle = recordedProgram?.description ?: recordedItem?.description
        mTransportControlGlue.isSeekEnabled = !isAnyLive
        mTransportControlGlue.playWhenPrepared()

        // Build OkHttpClient with auth if needed
        val movieUrl: String
        val okHttpClient: OkHttpClient

        if (isLiveMpegTs && liveChannelId >= 0) {
            val mpegTsUrl = EpgStationV2.getLiveMpegTsUrl(liveChannelId)
            okHttpClient = buildOkHttpClient(mpegTsUrl)
            // 切り分け中: 字幕なしのプレーン再生でクラッシュの原因がネイティブTS処理側か確認する
            startDirectPlayback(mpegTsUrl, okHttpClient, false)
            return
        }

        if (isLive && liveChannelId >= 0) {
            okHttpClient = buildOkHttpClient(EpgStationV2.getVideoURL("0"))
            startLiveHlsPlayback(liveChannelId, okHttpClient)
            return
        }

        if (isHls && recordedItem != null) {
            okHttpClient = buildOkHttpClient(EpgStationV2.getVideoURL("0"))
            startHlsPlayback(actionId, okHttpClient)
            return
        }

        if (recordedProgram != null) {
            movieUrl = if (actionId == VideoDetailsFragment.ACTION_WATCH_ORIGINAL_TS) {
                EpgStation.getTsVideoURL(recordedProgram.id.toString())
            } else {
                EpgStation.getEncodedVideoURL(recordedProgram.id.toString(), actionId.toString())
            }
        } else {
            movieUrl = EpgStationV2.getVideoURL(actionId.toString())
        }

        okHttpClient = buildOkHttpClient(movieUrl)
        val cleanUrl = stripAuthFromUrl(movieUrl)
        startDirectPlayback(cleanUrl, okHttpClient, isTsContent)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = super.onCreateView(inflater, container, savedInstanceState) as ViewGroup?
        if (isTsContent) {
            overlayView = SubtitleOverlayView(requireContext()).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            root?.addView(overlayView, 1)
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setControlsOverlayAutoHideEnabled(true)
        if (isLiveMpegTs) {
            hideSeekBar(view)
        }
    }

    /**
     * mpegts直送はシーク不可の生ストリームで、シークバーを見せても意味がない
     * （HLSは追いかけ再生時のバッファ状況が見えて便利なので残す）。
     * Leanbackにシークバーの表示/非表示を切り替えるAPIが無いため、コントロール行の
     * ビューが実際に生成されるのを待って直接 GONE にする。
     */
    private fun hideSeekBar(root: View) {
        val progressBar = root.findViewById<androidx.leanback.widget.SeekBar>(androidx.leanback.R.id.playback_progress)
        if (progressBar != null) {
            progressBar.visibility = View.GONE
            return
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val pb = root.findViewById<androidx.leanback.widget.SeekBar>(androidx.leanback.R.id.playback_progress)
                if (pb != null) {
                    pb.visibility = View.GONE
                    root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
    }

    private fun startDirectPlayback(url: String, httpClient: OkHttpClient, isTsContent: Boolean) {
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
        val mediaSource = if (isTsContent) {
            val tsFactory = TsReadexDataSource.Factory(dataSourceFactory)
            setupCaptionListeners(tsFactory)
            ProgressiveMediaSource.Factory(tsFactory)
                .createMediaSource(MediaItem.fromUri(url))
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))
        }
        exoPlayer?.setMediaSource(mediaSource)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    private fun startHlsPlayback(actionId: Long, httpClient: OkHttpClient) {
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
                val m3u8Url = EpgStationV2.getHlsStreamUrl(streamId)
                Log.d(TAG, "HLS stream started: streamId=$streamId m3u8Url=$m3u8Url")
                activity?.runOnUiThread {
                    val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
                    // EPGStation は HLS 開始直後 M3U8 が未生成で 404 を返すためリトライが必要
                    val hlsErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
                        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                            val cause = loadErrorInfo.exception
                            if (cause is HttpDataSource.InvalidResponseCodeException
                                && cause.responseCode == 404
                                && loadErrorInfo.errorCount <= 15) {
                                return 2_000L
                            }
                            return super.getRetryDelayMsFor(loadErrorInfo)
                        }
                    }
                    val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                        .setLoadErrorHandlingPolicy(hlsErrorPolicy)
                        .createMediaSource(MediaItem.fromUri(m3u8Url))
                    exoPlayer?.setMediaSource(mediaSource)
                    exoPlayer?.prepare()
                    exoPlayer?.playWhenReady = true
                    keepAliveHandler.post(keepAliveRunnable)
                }
            }
            override fun onFailure(call: Call<HlsStream>, t: Throwable) {
                Log.e(TAG, "HLS stream start failed", t)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), getString(R.string.hls_stream_start_failed), Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun startLiveHlsPlayback(channelId: Long, httpClient: OkHttpClient) {
        EpgStationV2.api?.startLiveHlsStream(channelId)?.enqueue(object : Callback<HlsStream> {
            override fun onResponse(call: Call<HlsStream>, response: Response<HlsStream>) {
                val streamId = response.body()?.streamId
                if (streamId == null) {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), getString(R.string.hls_stream_start_failed), Toast.LENGTH_LONG).show()
                    }
                    return
                }
                hlsStreamId = streamId
                val m3u8Url = EpgStationV2.getHlsStreamUrl(streamId)
                Log.d(TAG, "Live HLS stream started: streamId=$streamId m3u8Url=$m3u8Url")
                activity?.runOnUiThread {
                    val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
                    // ライブ配信は開始直後、EPGStation側のトランスコーダ(ffmpeg)がまだ
                    // セグメントを安定して出力できておらず、404だけでなく接続エラーや
                    // タイムアウトなど様々な失敗が起こりうる（実測で20秒程度かかることがある）。
                    // 種類を問わずウォームアップ中はリトライし続け、致命的エラーで
                    // 再生停止してしまうのを防ぐ。
                    val hlsErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
                        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                            if (loadErrorInfo.errorCount <= LIVE_WARMUP_RETRY_COUNT) {
                                return LIVE_WARMUP_RETRY_DELAY_MS
                            }
                            return super.getRetryDelayMsFor(loadErrorInfo)
                        }
                    }
                    val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                        .setLoadErrorHandlingPolicy(hlsErrorPolicy)
                        .createMediaSource(MediaItem.fromUri(m3u8Url))
                    exoPlayer?.setMediaSource(mediaSource)
                    exoPlayer?.prepare()
                    exoPlayer?.playWhenReady = true
                    keepAliveHandler.post(keepAliveRunnable)
                }
            }
            override fun onFailure(call: Call<HlsStream>, t: Throwable) {
                Log.e(TAG, "Live HLS stream start failed", t)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), getString(R.string.hls_stream_start_failed), Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun setupCaptionListeners(tsFactory: TsReadexDataSource.Factory) {
        captionHandle = AribCaptionFilter.create(1920, 1080, AribCaptionFilter.TYPE_CAPTION)
        superimposeHandle = AribCaptionFilter.create(1920, 1080, AribCaptionFilter.TYPE_SUPERIMPOSE)

        tsFactory.captionPesListener = PesCallback { ptsMs, pesPayload ->
            mainHandler.post {
                val h = captionHandle
                if (h == 0L || !captionEnabled) return@post
                if (AribCaptionFilter.decode(h, ptsMs, pesPayload, 0, pesPayload.size)) {
                    scheduleWithBufferDelay { scheduleCaptionRender(ptsMs) }
                }
            }
        }

        tsFactory.superimposePesListener = PesCallback { ptsMs, pesPayload ->
            mainHandler.post {
                val h = superimposeHandle
                if (h == 0L || !superimposeEnabled) return@post
                if (AribCaptionFilter.decode(h, ptsMs, pesPayload, 0, pesPayload.size)) {
                    scheduleWithBufferDelay { scheduleSuperimposeRender(ptsMs) }
                }
            }
        }
    }

    private fun scheduleWithBufferDelay(action: () -> Unit) {
        val p = exoPlayer
        val delayMs = if (p != null) {
            (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
        } else 0L
        if (delayMs > 50) {
            mainHandler.postDelayed(action, delayMs)
        } else {
            action()
        }
    }

    private fun scheduleCaptionRender(ptsMs: Long) {
        val h = captionHandle
        if (h == 0L || !captionEnabled) return
        val images = AribCaptionFilter.render(h, ptsMs)
        if (images.isNotEmpty()) {
            overlayView?.showCaptions(images)
        }
    }

    private fun scheduleSuperimposeRender(ptsMs: Long) {
        val h = superimposeHandle
        if (h == 0L || !superimposeEnabled) return
        val images = AribCaptionFilter.render(h, ptsMs)
        if (images.isNotEmpty()) {
            overlayView?.showSuperimpose(images)
        }
    }

    private fun selectAudioTrack(groupIndex: Int) {
        val player = exoPlayer ?: return
        if (groupIndex >= audioGroups.size) return
        val targetGroup = audioGroups[groupIndex].mediaTrackGroup
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(
                androidx.media3.common.TrackSelectionOverride(targetGroup, listOf(0))
            )
            .build()
        Log.d(TAG, "selectAudioTrack: groupIndex=$groupIndex")
    }

    fun toggleCaption() {
        captionEnabled = !captionEnabled
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit().putBoolean(PREF_CAPTION_ENABLED, captionEnabled).apply()
        if (!captionEnabled) overlayView?.clearCaptions()
        val msg = if (captionEnabled) R.string.caption_on else R.string.caption_off
        Toast.makeText(requireContext(), getString(msg), Toast.LENGTH_SHORT).show()
    }

    fun toggleSuperimpose() {
        superimposeEnabled = !superimposeEnabled
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit().putBoolean(PREF_SUPERIMPOSE_ENABLED, superimposeEnabled).apply()
        if (!superimposeEnabled) overlayView?.clearSuperimpose()
        val msg = if (superimposeEnabled) R.string.superimpose_on else R.string.superimpose_off
        Toast.makeText(requireContext(), getString(msg), Toast.LENGTH_SHORT).show()
    }

    fun toggleAudioTrack() {
        if (!hasSubAudio) {
            Toast.makeText(requireContext(), getString(R.string.no_sub_audio), Toast.LENGTH_SHORT).show()
            return
        }
        preferSubAudio = !preferSubAudio
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit().putBoolean(PREF_SUB_AUDIO, preferSubAudio).apply()
        selectAudioTrack(if (preferSubAudio) 1 else 0)
        val msg = if (preferSubAudio) R.string.audio_sub else R.string.audio_main
        Toast.makeText(requireContext(), getString(msg), Toast.LENGTH_SHORT).show()
    }

    /** 録画予約に失敗した際、自己解決やissue報告に使えるよう技術的な詳細をダイアログで表示する */
    private fun showRecordErrorDialog(detail: String) {
        Log.e(TAG, detail)
        activity?.runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_MinWidth)
                .setTitle(getString(R.string.record_failed))
                .setMessage(detail)
                .setPositiveButton(getString(R.string.close)) { _, _ -> }
                .create().show()
        }
    }

    fun startRecordingCurrentProgram() {
        if (liveChannelId < 0) return
        EpgStationV2.api?.getScheduleOnAir()?.enqueue(object : Callback<List<Schedule>> {
            override fun onResponse(call: Call<List<Schedule>>, response: Response<List<Schedule>>) {
                if (!response.isSuccessful) {
                    mTransportControlGlue.resetRecordActionLabel()
                    showRecordErrorDialog("${getString(R.string.schedule_fetch_error)}\nHTTP${response.code()}: ${response.errorBody()?.string()}")
                    return
                }
                val schedules = response.body()
                val programId = schedules
                    ?.firstOrNull { it.channel.id == liveChannelId }
                    ?.programs?.firstOrNull()?.id
                if (programId == null) {
                    mTransportControlGlue.resetRecordActionLabel()
                    Log.d(TAG, "startRecordingCurrentProgram: no current program channelId=$liveChannelId channels=${schedules?.map { it.channel.id }}")
                    activity?.runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            "${getString(R.string.record_failed)} : ${getString(R.string.no_current_program_info)}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }
                EpgStationV2.api?.addReserve(ManualReserveOption(programId = programId))
                    ?.enqueue(object : Callback<okhttp3.ResponseBody> {
                        override fun onResponse(call: Call<okhttp3.ResponseBody>, response: Response<okhttp3.ResponseBody>) {
                            mTransportControlGlue.resetRecordActionLabel()
                            if (response.isSuccessful) {
                                activity?.runOnUiThread {
                                    Toast.makeText(requireContext(), getString(R.string.record_reserved), Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                showRecordErrorDialog("${getString(R.string.record_reserve_error)}\nHTTP${response.code()} programId=$programId: ${response.errorBody()?.string()}")
                            }
                        }
                        override fun onFailure(call: Call<okhttp3.ResponseBody>, t: Throwable) {
                            mTransportControlGlue.resetRecordActionLabel()
                            showRecordErrorDialog("${getString(R.string.record_reserve_network_error)}\nprogramId=$programId: ${t.javaClass.simpleName} ${t.message}")
                        }
                    })
            }
            override fun onFailure(call: Call<List<Schedule>>, t: Throwable) {
                mTransportControlGlue.resetRecordActionLabel()
                showRecordErrorDialog("${getString(R.string.schedule_fetch_network_error)}\n${t.javaClass.simpleName} ${t.message}")
            }
        })
    }

    fun showCurrentProgramInfo() {
        if (liveChannelId < 0) return
        EpgStationV2.api?.getScheduleOnAir()?.enqueue(object : Callback<List<Schedule>> {
            override fun onResponse(call: Call<List<Schedule>>, response: Response<List<Schedule>>) {
                val program = response.body()
                    ?.firstOrNull { it.channel.id == liveChannelId }
                    ?.programs?.firstOrNull()
                if (program == null) {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
                    }
                    return
                }
                val jst = java.util.TimeZone.getTimeZone("Asia/Tokyo")
                val dfDateAndTime = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.SHORT).also { it.timeZone = jst }
                val dfTime = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).also { it.timeZone = jst }
                val channelName = EpgStationV2.channelMap[liveChannelId] ?: ""
                val genreText = AribGenre.getGenreText(program.genre1, program.subGenre1)
                val timeInfo = getString(
                    R.string.start_end_duration,
                    dfDateAndTime.format(java.util.Date(program.startAt)),
                    dfTime.format(java.util.Date(program.endAt)),
                    (program.endAt - program.startAt) / 60 / 1000
                )
                val body = buildString {
                    if (channelName.isNotEmpty()) { append(channelName); append("\n") }
                    if (genreText.isNotEmpty()) { append(genreText); append("\n") }
                    append(timeInfo)
                    if (!program.description.isNullOrEmpty()) { append("\n\n"); append(program.description) }
                    if (!program.extended.isNullOrEmpty()) { append("\n"); append(program.extended) }
                }
                activity?.runOnUiThread {
                    ProgramInfoDialogFragment.newInstance(program.name, body)
                        .show(childFragmentManager, ProgramInfoDialogFragment.TAG)
                }
            }
            override fun onFailure(call: Call<List<Schedule>>, t: Throwable) {
                Log.e(TAG, "showCurrentProgramInfo: getScheduleOnAir failed", t)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun destroyAribSessions() {
        if (captionHandle != 0L) {
            AribCaptionFilter.destroy(captionHandle)
            captionHandle = 0
        }
        if (superimposeHandle != 0L) {
            AribCaptionFilter.destroy(superimposeHandle)
            superimposeHandle = 0
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
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
        mainHandler.removeCallbacksAndMessages(null)
        overlayView?.clearAll()
        destroyAribSessions()
        exoPlayer?.release()
        exoPlayer = null
        overlayView = null
    }

    override fun onPause() {
        super.onPause()
        mTransportControlGlue.pause()
    }

    companion object {
        private const val TAG = "PlaybackVideoFragment"
        private const val UPDATE_PERIOD_MS = 200
        private const val KEEP_ALIVE_INTERVAL_MS = 10_000L
        // ライブHLSウォームアップ中のリトライ回数・間隔（20回 x 2秒 = 最大40秒程度待つ）
        private const val LIVE_WARMUP_RETRY_COUNT = 20
        private const val LIVE_WARMUP_RETRY_DELAY_MS = 2_000L
        private const val PREF_CAPTION_ENABLED = "pref_caption_enabled"
        private const val PREF_SUPERIMPOSE_ENABLED = "pref_superimpose_enabled"
        private const val PREF_SUB_AUDIO = "pref_sub_audio"

        private fun buildOkHttpClient(sampleUrl: String): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
            try {
                val userInfo = URL(sampleUrl).userInfo
                if (userInfo != null && userInfo.contains(":")) {
                    val parts = userInfo.split(":", limit = 2)
                    val credentials = Credentials.basic(parts[0], parts[1])
                    builder.addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Authorization", credentials)
                                .build()
                        )
                    }
                }
            } catch (_: Exception) {}
            return builder.build()
        }

        private fun stripAuthFromUrl(url: String): String {
            return try {
                val parsed = URL(url)
                if (parsed.userInfo != null) {
                    URL(parsed.protocol, parsed.host, parsed.port, parsed.file).toString()
                } else {
                    url
                }
            } catch (_: Exception) { url }
        }
    }

    class MyPlaybackTransportControlGlue(
        context: Context?,
        impl: PlayerAdapter,
        private val isTsContent: Boolean,
        private val isLive: Boolean,
        captionEnabled: Boolean,
        superimposeEnabled: Boolean,
        preferSubAudio: Boolean,
        hasSubAudio: Boolean,
    ) : PlaybackTransportControlGlue<PlayerAdapter>(context, impl) {

        private val ccAction = PlaybackControlsRow.ClosedCaptioningAction(getContext()).apply {
            index = if (captionEnabled) PlaybackControlsRow.ClosedCaptioningAction.INDEX_ON
                    else PlaybackControlsRow.ClosedCaptioningAction.INDEX_OFF
        }

        private val superimposeAction = Action(
            ACTION_ID_SUPERIMPOSE,
            if (superimposeEnabled) "SI:ON" else "SI:OFF"
        )

        private val audioAction = Action(ACTION_ID_AUDIO, if (preferSubAudio) "Sub" else "Main").apply {
            // Will be updated when tracks are detected
        }
        private var audioActionEnabled = hasSubAudio

        private val recordAction = Action(
            ACTION_ID_RECORD,
            "REC",
            "",
            getContext()?.getDrawable(R.drawable.ic_sidebar_rec)
        )

        private val infoAction = Action(
            ACTION_ID_INFO,
            getContext()?.getString(R.string.program_info) ?: "",
            "",
            getContext()?.getDrawable(R.drawable.ic_action_info)
        )

        private var primaryActions: ArrayObjectAdapter? = null

        override fun onCreatePrimaryActions(primaryActionsAdapter: ArrayObjectAdapter) {
            super.onCreatePrimaryActions(primaryActionsAdapter)
            if (isTsContent) {
                primaryActionsAdapter.add(ccAction)
                primaryActionsAdapter.add(superimposeAction)
                primaryActionsAdapter.add(audioAction)
            }
            if (isLive) {
                primaryActionsAdapter.add(recordAction)
                primaryActionsAdapter.add(infoAction)
            }
            if (isTsContent || isLive) {
                primaryActions = primaryActionsAdapter
            }
        }

        fun updateAudioActionState(enabled: Boolean) {
            audioActionEnabled = enabled
            audioAction.label1 = if (!enabled) "---"
                else if ((host?.let {
                    (it as? VideoSupportFragmentGlueHost)
                } != null)) {
                    val fragment = this@MyPlaybackTransportControlGlue.context
                    if (fragment is Context) {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(fragment)
                        if (prefs.getBoolean(PREF_SUB_AUDIO, false)) "Sub" else "Main"
                    } else "Main"
                } else "Main"
            primaryActions?.let { adapter ->
                val idx = adapter.indexOf(audioAction)
                if (idx >= 0) adapter.notifyArrayItemRangeChanged(idx, 1)
            }
        }

        override fun onActionClicked(action: Action?) {
            val fragment = (host as? VideoSupportFragmentGlueHost)?.let {
                // Access the fragment through the context
                null
            }
            // Get the PlaybackVideoFragment from the activity
            val playbackFragment = (context as? androidx.fragment.app.FragmentActivity)
                ?.supportFragmentManager
                ?.fragments
                ?.filterIsInstance<PlaybackVideoFragment>()
                ?.firstOrNull()

            when (action) {
                ccAction -> {
                    playbackFragment?.toggleCaption()
                    ccAction.index = if (ccAction.index == PlaybackControlsRow.ClosedCaptioningAction.INDEX_ON)
                        PlaybackControlsRow.ClosedCaptioningAction.INDEX_OFF
                    else PlaybackControlsRow.ClosedCaptioningAction.INDEX_ON
                    primaryActions?.let { adapter ->
                        val idx = adapter.indexOf(ccAction)
                        if (idx >= 0) adapter.notifyArrayItemRangeChanged(idx, 1)
                    }
                }
                superimposeAction -> {
                    playbackFragment?.toggleSuperimpose()
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context!!)
                    val enabled = prefs.getBoolean(PREF_SUPERIMPOSE_ENABLED, true)
                    superimposeAction.label1 = if (enabled) "SI:ON" else "SI:OFF"
                    primaryActions?.let { adapter ->
                        val idx = adapter.indexOf(superimposeAction)
                        if (idx >= 0) adapter.notifyArrayItemRangeChanged(idx, 1)
                    }
                }
                audioAction -> {
                    if (!audioActionEnabled) {
                        Toast.makeText(context, context?.getString(R.string.no_sub_audio), Toast.LENGTH_SHORT).show()
                        return
                    }
                    playbackFragment?.toggleAudioTrack()
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context!!)
                    val isSub = prefs.getBoolean(PREF_SUB_AUDIO, false)
                    audioAction.label1 = if (isSub) "Sub" else "Main"
                    primaryActions?.let { adapter ->
                        val idx = adapter.indexOf(audioAction)
                        if (idx >= 0) adapter.notifyArrayItemRangeChanged(idx, 1)
                    }
                }
                recordAction -> {
                    recordAction.label1 = "REC..."
                    primaryActions?.let { adapter ->
                        val idx = adapter.indexOf(recordAction)
                        if (idx >= 0) adapter.notifyArrayItemRangeChanged(idx, 1)
                    }
                    playbackFragment?.startRecordingCurrentProgram()
                }
                infoAction -> {
                    playbackFragment?.showCurrentProgramInfo()
                }
                else -> super.onActionClicked(action)
            }
        }

        fun resetRecordActionLabel() {
            recordAction.label1 = "REC"
            primaryActions?.let { adapter ->
                val idx = adapter.indexOf(recordAction)
                if (idx >= 0) adapter.notifyArrayItemRangeChanged(idx, 1)
            }
        }

        companion object {
            private const val ACTION_ID_SUPERIMPOSE = 10001L
            private const val ACTION_ID_AUDIO = 10002L
            private const val ACTION_ID_RECORD = 10003L
            private const val ACTION_ID_INFO = 10004L
        }
    }
}
