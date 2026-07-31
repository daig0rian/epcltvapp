package com.daigorian.epcltvapp

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import okhttp3.Request
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
    // 字幕PES処理・遅延レンダリングのHandlerコールバックに付けるトークン。
    // シーク時にmainHandler全体ではなくこれらだけを選択的にキャンセルするために使う
    // (mainHandlerは他の目的にも使い回している共有インスタンスのため)。
    private val captionCallbackToken = Any()

    // Persisted toggle states
    private var captionEnabled = false
    private var superimposeEnabled = false
    private var preferSubAudio = false

    // Audio track state
    private val audioGroups = mutableListOf<Tracks.Group>()
    private var hasSubAudio = false

    // Content type
    private var isTsContent = false
    // ARIB字幕/デュアルモノ副音声を扱うtsreadexネイティブフィルタを使うかどうか。
    // isTsContentのサブセット(TSでなければ常にfalse)。
    private var useNativeTsProcessing = false

    // TS seek support (録画オリジナルTSのみ)
    private var tsSeekAdapter: TsSeekPlayerAdapter? = null
    private var tsSeekUrl: String? = null
    private var tsSeekHttpClient: OkHttpClient? = null
    private var tsSeekDataProvider: TsSeekDataProvider? = null
    private val tsProbeExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor()

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
        val isAnyLive = isLive || isLiveMpegTs
        liveChannelId = activity?.intent?.getLongExtra(DetailsActivity.CHANNEL_ID, -1L) ?: -1L
        val liveChannelName = activity?.intent?.getStringExtra(DetailsActivity.CHANNEL_NAME)

        // Restore persisted states
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        captionEnabled = prefs.getBoolean(PREF_CAPTION_ENABLED, false)
        superimposeEnabled = prefs.getBoolean(PREF_SUPERIMPOSE_ENABLED, true)
        preferSubAudio = prefs.getBoolean(PREF_SUB_AUDIO, false)

        // isTsContent は「TSファイルかどうか」のみを表すフラグ。
        // ネイティブ処理(tsreadex/ARIB字幕/デュアルモノ副音声)を使うかどうかは
        // useNativeTsProcessing として別管理する——TS向けシーク機能はネイティブ処理を
        // 使わなくても(生バイトを直接読むだけなので)動作するため、両者は独立している。
        //
        // ライブmpegts直送は#33のクラッシュ疑いにより長らくネイティブTS処理を強制バイパス
        // していたが、Issue #34でユーザー切り替え可能な設定にした。#33が実機で未解決のため、
        // デフォルトはOFF（従来通りバイパス）とし、必要な人だけONにする。
        val nativeTsProcessingPref = prefs.getBoolean(getString(R.string.pref_key_native_ts_processing), false)
        isTsContent = (activity?.intent?.getBooleanExtra(DetailsActivity.IS_TS_CONTENT, false) ?: false) || isLiveMpegTs
        useNativeTsProcessing = isTsContent && nativeTsProcessingPref

        // ストリームプロファイル選択（Issue #34）: config.ymlの並び順ではなくプロファイル名で
        // ユーザーの選択を永続化してあるので、都度最新のstreamConfigから該当indexを解決する。
        val recordedHlsMode = EpgStationV2.resolveHlsProfileIndex(
            prefs.getString(getString(R.string.pref_key_recorded_hls_profile), ""),
            EpgStationV2.streamConfig?.recorded?.ts?.hls.orEmpty()
        )
        val liveHlsMode = EpgStationV2.resolveHlsProfileIndex(
            prefs.getString(getString(R.string.pref_key_live_hls_profile), ""),
            EpgStationV2.streamConfig?.live?.ts?.hls.orEmpty()
        )
        val liveMpegTsMode = EpgStationV2.resolveM2tsProfileIndex(
            prefs.getString(getString(R.string.pref_key_live_mpegts_profile), ""),
            EpgStationV2.streamConfig?.live?.ts?.m2ts.orEmpty()
        )

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
        // 録画オリジナルTS再生時は、TsReadexDataSource が duration を C.TIME_UNSET として
        // 隠しているため、TsProbe の実測値とシーク時のオフセットを自前管理できる
        // TsSeekPlayerAdapter を使う（LeanbackPlayerAdapter は final のため継承不可）。
        val playerAdapter: PlayerAdapter = if (isTsContent && !isAnyLive) {
            TsSeekPlayerAdapter(exoPlayer!!, UPDATE_PERIOD_MS) { targetMs ->
                performTsSeek(targetMs)
            }.also { tsSeekAdapter = it }
        } else {
            LeanbackPlayerAdapter(requireContext(), exoPlayer!!, UPDATE_PERIOD_MS)
        }
        val glueHost = VideoSupportFragmentGlueHost(this@PlaybackVideoFragment)

        mTransportControlGlue = MyPlaybackTransportControlGlue(
            activity, playerAdapter, useNativeTsProcessing, isAnyLive, captionEnabled, superimposeEnabled, preferSubAudio, hasSubAudio
        )
        mTransportControlGlue.host = glueHost
        mTransportControlGlue.title = recordedProgram?.name ?: recordedItem?.name ?: liveChannelName
        mTransportControlGlue.subtitle = recordedProgram?.description ?: recordedItem?.description
        // 録画オリジナルTSはシーク点テーブルの構築が終わるまでシーク不可にする
        // (テーブル未完成の間にシーク操作されると、Leanbackのデフォルトの1%刻み挙動＋
        // 都度のバイト位置探索という避けたかった経路に落ちてしまうため)。
        // テーブル完成後に startTsProbing() 内で true に切り替える。
        mTransportControlGlue.isSeekEnabled = if (isTsContent && !isAnyLive) false else !isAnyLive
        mTransportControlGlue.playWhenPrepared()

        // Build OkHttpClient with auth if needed
        val movieUrl: String
        val okHttpClient: OkHttpClient

        if (isLiveMpegTs && liveChannelId >= 0) {
            val mpegTsUrl = EpgStationV2.getLiveMpegTsUrl(liveChannelId, liveMpegTsMode)
            okHttpClient = buildOkHttpClient(mpegTsUrl)
            startDirectPlayback(mpegTsUrl, okHttpClient, isTsContent, useNativeTsProcessing)
            return
        }

        if (isLive && liveChannelId >= 0) {
            okHttpClient = buildOkHttpClient(EpgStationV2.getVideoURL("0"))
            startLiveHlsPlayback(liveChannelId, okHttpClient, liveHlsMode)
            return
        }

        if (isHls && recordedItem != null) {
            okHttpClient = buildOkHttpClient(EpgStationV2.getVideoURL("0"))
            startHlsPlayback(actionId, okHttpClient, recordedHlsMode)
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
        startDirectPlayback(cleanUrl, okHttpClient, isTsContent, useNativeTsProcessing)
        if (isTsContent) {
            tsSeekUrl = cleanUrl
            tsSeekHttpClient = okHttpClient
            startTsProbing(cleanUrl, okHttpClient)
            investigateTsreadexNormalizedThumbnail(cleanUrl, okHttpClient)
        }
    }

    /**
     * [Phase 2調査・一時コード] 生ARIB TSを直接MediaMetadataRetrieverに渡す案は、
     * 実機検証(ATSParserが「スクランブルされたストリーム」と誤判定して解析を中断)により
     * 不採用と判明済み。次の案として、tsreadexフィルタで正規化した後のバイト列を
     * MediaDataSource(API23+)経由でMediaMetadataRetrieverに渡せるか検証する。
     * ファイル先頭付近を数MB読み、tsreadexで正規化し、その結果をメモリ上のまま渡す。
     * 検証結果が出たら本メソッドと呼び出しは削除するか、正式な実装に置き換える。
     */
    private fun investigateTsreadexNormalizedThumbnail(url: String, client: OkHttpClient) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "[Phase2調査] MediaDataSourceはAPI23未満で使えないため調査スキップ")
            return
        }
        tsProbeExecutor.execute {
            val rawBytes = try {
                val request = Request.Builder().url(url)
                    .header("Range", "bytes=0-${THUMBNAIL_PROBE_RAW_BYTES - 1}")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.bytes()
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Phase2調査] 生データ取得で例外発生", e)
                null
            }
            if (rawBytes == null) {
                Log.w(TAG, "[Phase2調査] 生データ取得失敗")
                return@execute
            }

            val filterHandle = TsReadexFilter.create(
                programNumberOrIndex = -1,
                audio1Mode = 1 + 4 + 8,
                audio2Mode = 1 + 4,
                captionMode = 1,
                superimposeMode = 1,
            )
            val normalized = try {
                val output = java.io.ByteArrayOutputStream()
                val usableBytes = (rawBytes.size / 188) * 188
                var offset = 0
                val batchSize = 188 * 64
                while (offset < usableBytes) {
                    val len = minOf(batchSize, usableBytes - offset)
                    val alignedLen = (len / 188) * 188
                    if (alignedLen <= 0) break
                    val processed = TsReadexFilter.processPackets(filterHandle, rawBytes.copyOfRange(offset, offset + alignedLen), alignedLen)
                    if (processed.isNotEmpty()) output.write(processed)
                    offset += alignedLen
                }
                output.toByteArray()
            } finally {
                TsReadexFilter.destroy(filterHandle)
            }
            Log.i(TAG, "[Phase2調査] tsreadex正規化: raw=${rawBytes.size}bytes normalized=${normalized.size}bytes")
            if (normalized.isEmpty()) {
                Log.w(TAG, "[Phase2調査] 正規化後データが空のため中止")
                return@execute
            }

            // [Phase2調査] 1回目の検証で判明: tsreadex正規化後もtransport_scrambling_control
            // ビット(TSヘッダbyte3の上位2bit)が残っており、Android標準のATSParserが
            // 「スクランブルされたストリーム」と誤判定して解析を中断していた……という仮説だったが、
            // 2回目の検証でこのビットは既に0であることが判明(scrambledPacketCount=0)。
            // それでも同じ誤判定が起きたため、ビットの問題ではないと分かった。
            var scrambledPacketCount = 0
            var i = 0
            while (i + 188 <= normalized.size) {
                if (normalized[i] == 0x47.toByte() && (normalized[i + 3].toInt() and 0xC0) != 0) {
                    scrambledPacketCount++
                    normalized[i + 3] = (normalized[i + 3].toInt() and 0x3F).toByte()
                }
                i += 188
            }
            Log.i(TAG, "[Phase2調査] transport_scrambling_controlをクリアしたパケット数=$scrambledPacketCount")

            // [Phase2調査] 3回目の仮説: 個別ストリームではなく5ストリーム全部が一律で
            // descrambling対象扱いされていたことから、CAT(PID=0x0001, tsreadexの-nフィルタは
            // 0x0030未満のPIDをそのまま素通しするため残っている)がCA(限定受信)利用を宣言して
            // おり、それをATSParserが見て番組全体をスクランブル扱いしている可能性を検証する。
            val catOutput = java.io.ByteArrayOutputStream()
            var catPacketCount = 0
            var j = 0
            while (j + 188 <= normalized.size) {
                val isCat = normalized[j] == 0x47.toByte() &&
                    (((normalized[j + 1].toInt() and 0x1F) shl 8) or (normalized[j + 2].toInt() and 0xFF)) == 0x0001
                if (isCat) {
                    catPacketCount++
                } else {
                    catOutput.write(normalized, j, 188)
                }
                j += 188
            }
            val catFiltered = catOutput.toByteArray()
            Log.i(TAG, "[Phase2調査] CAT(PID=0x0001)パケットを${catPacketCount}個除去 filtered=${catFiltered.size}bytes")

            // [Phase2調査] 2つの仮説が外れたため、推測ではなくPMTの生バイトを直接確認する。
            // tsreadexの-nフィルタ通過後、PMTはPID=0x01f0に固定される(Readme.txt参照)。
            dumpPmtHex(catFiltered, 0x01f0)

            val retriever = MediaMetadataRetriever()
            val startedAt = SystemClock.elapsedRealtime()
            try {
                retriever.setDataSource(ThumbnailInvestigationDataSource(catFiltered))
                val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                if (frame != null) {
                    Log.i(TAG, "[Phase2調査] 正規化後MediaMetadataRetriever: 成功 width=${frame.width} height=${frame.height} elapsedMs=$elapsedMs")
                } else {
                    Log.w(TAG, "[Phase2調査] 正規化後MediaMetadataRetriever: getFrameAtTimeがnullを返した elapsedMs=$elapsedMs")
                }
            } catch (e: Exception) {
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                Log.w(TAG, "[Phase2調査] 正規化後MediaMetadataRetriever: 例外発生 elapsedMs=$elapsedMs", e)
            } finally {
                retriever.release()
            }
        }
    }

    /** [Phase2調査・一時コード] 指定PIDのPSIセクションを再構成してhexダンプする。 */
    private fun dumpPmtHex(bytes: ByteArray, targetPid: Int) {
        var pos = 0
        var buf = ByteArray(0)
        var expectedLen = -1
        while (pos + 188 <= bytes.size) {
            if (bytes[pos] != 0x47.toByte()) {
                pos += 188
                continue
            }
            val pid = ((bytes[pos + 1].toInt() and 0x1F) shl 8) or (bytes[pos + 2].toInt() and 0xFF)
            if (pid != targetPid) {
                pos += 188
                continue
            }
            val pusi = (bytes[pos + 1].toInt() and 0x40) != 0
            val afc = (bytes[pos + 3].toInt() shr 4) and 0x03
            if ((afc and 0x01) == 0) {
                pos += 188
                continue
            }
            val afLen = if ((afc and 0x02) != 0) (bytes[pos + 4].toInt() and 0xFF) + 1 else 0
            val payloadStart = pos + 4 + afLen
            val payloadEnd = pos + 188
            if (payloadStart >= payloadEnd) {
                pos += 188
                continue
            }
            if (pusi) {
                var p = payloadStart
                val pointerField = bytes[p].toInt() and 0xFF
                p += 1 + pointerField
                if (p >= payloadEnd) {
                    pos += 188
                    continue
                }
                buf = bytes.copyOfRange(p, payloadEnd)
                if (buf.size < 3) {
                    expectedLen = -1
                    pos += 188
                    continue
                }
                val sectionLength = ((buf[1].toInt() and 0x0F) shl 8) or (buf[2].toInt() and 0xFF)
                expectedLen = 3 + sectionLength
            } else {
                if (expectedLen < 0) {
                    pos += 188
                    continue
                }
                buf += bytes.copyOfRange(payloadStart, payloadEnd)
            }
            if (expectedLen in 1..buf.size) {
                val section = buf.copyOf(expectedLen)
                Log.i(TAG, "[Phase2調査] PID=0x${targetPid.toString(16)} section(${section.size}bytes) hex=" +
                    section.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
                return
            }
            pos += 188
        }
        Log.w(TAG, "[Phase2調査] PID=0x${targetPid.toString(16)}のセクションが見つからなかった")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private class ThumbnailInvestigationDataSource(private val data: ByteArray) : android.media.MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val length = minOf(size, (data.size - position).toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, length)
            return length
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = super.onCreateView(inflater, container, savedInstanceState) as ViewGroup?
        if (useNativeTsProcessing) {
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

    private fun startDirectPlayback(
        url: String,
        httpClient: OkHttpClient,
        isTsContent: Boolean,
        useNativeTsProcessing: Boolean,
    ) {
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
        val mediaSource = if (isTsContent) {
            // TsReadexDataSource は startByteOffset によるシーク制御を担うため、
            // ネイティブ処理(ARIB字幕等)のオン/オフにかかわらず常に経由させる。
            val tsFactory = TsReadexDataSource.Factory(dataSourceFactory).apply {
                nativeProcessingEnabled = useNativeTsProcessing
            }
            if (useNativeTsProcessing) {
                setupCaptionListeners(tsFactory)
            }
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

    /**
     * 録画オリジナルTSのシーク対応: ファイル先頭・末尾を軽量プロービングして実測の
     * 総時間を求める。EPGStationのメタデータ(番組時間・ファイルサイズ)は使わない
     * ——録画がエラー等で途中終了し、メタデータと実データが乖離するケースがあるため。
     */
    private fun startTsProbing(url: String, client: OkHttpClient) {
        tsProbeExecutor.execute {
            val fileSize = TsProbe.fetchFileSize(url, client)
            val head = if (fileSize != null) TsProbe.probeHead(url, client) else null
            if (fileSize == null || head == null) {
                Log.w(TAG, "startTsProbing: head probe failed, seek stays disabled")
                return@execute
            }
            val tail = TsProbe.probeTail(url, client, fileSize, head.pcrPid)
            if (tail == null) {
                Log.w(TAG, "startTsProbing: tail probe failed, seek stays disabled")
                return@execute
            }
            if (tail.timeMs - head.firstPcr.timeMs <= 0) {
                Log.w(TAG, "startTsProbing: invalid duration=${tail.timeMs - head.firstPcr.timeMs}")
                return@execute
            }
            // head/tailの2点だけでdurationとシークを即座に有効化する(再生開始を待たせない)。
            // 各シーク位置の正確なバイト位置はここでは求めず、確定時に1回だけ軽量プローブして
            // 補正する(performTsSeek参照)。これにより起動時に必要なプロービングは
            // fetchFileSize+probeHead+probeTailの3リクエストのみで済む。
            val provider = TsSeekDataProvider(
                fileSize, head.pcrPid, head.firstPcr, tail, SEEK_POINT_INTERVAL_MS, SEEK_POINT_COUNT_MAX
            ) {
                // Leanbackがシーク開始時に無条件でpause()する挙動を打ち消す(直前に再生中だった場合のみ再開)。
                // シークバーへ移動しただけで再生が止まると「シークが実行された」と誤解させてしまうため。
                tsSeekAdapter?.resumePlaybackIfWasPlaying()
            }
            mainHandler.post {
                if (!isAdded) return@post
                tsSeekDataProvider = provider
                tsSeekAdapter?.setKnownDuration(provider.durationMs)
                mTransportControlGlue.setSeekProvider(provider)
                mTransportControlGlue.isSeekEnabled = true
                Log.d(TAG, "startTsProbing: seek ready (${provider.seekPositionCount} points), durationMs=${provider.durationMs}")
            }
        }
    }

    /**
     * シークバー確定(DPAD_CENTER/ENTER)時にTsSeekPlayerAdapterから呼ばれるエントリポイント。
     *
     * PlaybackSeekDataProvider(TsSeekDataProvider)を設定している間、LeanbackはD-pad操作の
     * 途中経過ではPlayerAdapter.seekTo()を呼ばず、確定時に1回だけ呼ぶ。ここでは概算バイト位置
     * (線形補間)を求めた上で、その近傍を1回だけ軽量プローブして実際の位置に補正する。
     *
     * 確定直後(mIsSeekがfalseに変わり通常ポーリングが再開する瞬間)から補正プローブ完了までの
     * 短い空白期間、シークバーが一瞬古い位置に戻ってから正しい位置へ進むという不自然な動きに
     * なるのを防ぐため、notifySeekPending()で概算位置を即座に(ネットワークI/O前に)反映する。
     */
    private fun performTsSeek(targetPositionMs: Long) {
        val provider = tsSeekDataProvider ?: return
        val url = tsSeekUrl ?: return
        val client = tsSeekHttpClient ?: return
        val clampedTarget = targetPositionMs.coerceIn(0, provider.durationMs)
        val guessByteOffset = provider.estimateByteOffset(clampedTarget)
        tsSeekAdapter?.notifySeekPending(clampedTarget)
        tsProbeExecutor.execute {
            val refined = TsProbe.refineSeekPoint(url, client, provider.fileSize, provider.pcrPid, guessByteOffset)
            mainHandler.post {
                if (!isAdded) return@post
                if (refined != null) {
                    restartTsPlaybackAt(refined.byteOffset, provider.toRelativeMs(refined.timeMs))
                } else {
                    // 補正プローブに失敗した場合は概算値のまま着地する(何もしないよりまし)
                    Log.w(TAG, "performTsSeek: refine probe failed, falling back to estimate")
                    restartTsPlaybackAt(guessByteOffset, clampedTarget)
                }
            }
        }
    }

    /** 解決したバイト位置から MediaSource を作り直し、疑似シークを実行する。 */
    private fun restartTsPlaybackAt(byteOffset: Long, newPositionOffsetMs: Long) {
        val url = tsSeekUrl ?: return
        val client = tsSeekHttpClient ?: return
        // 一時停止中にシークした場合は一時停止のままにする(自動再開させない)
        val wasPlaying = exoPlayer?.playWhenReady ?: true
        // シーク前の字幕PES処理・遅延レンダリングコールバックが残っていると、シーク後も
        // 古い字幕がしばらく表示され続けてしまうため、キャンセルした上でオーバーレイと
        // デコーダ内部状態(表示継続時間等)の両方をクリアする。
        mainHandler.removeCallbacksAndMessages(captionCallbackToken)
        overlayView?.clearCaptions()
        overlayView?.clearSuperimpose()
        if (captionHandle != 0L) AribCaptionFilter.flush(captionHandle)
        if (superimposeHandle != 0L) AribCaptionFilter.flush(superimposeHandle)
        val dataSourceFactory = OkHttpDataSource.Factory(client)
        val tsFactory = TsReadexDataSource.Factory(dataSourceFactory).apply {
            startByteOffset = byteOffset
            nativeProcessingEnabled = useNativeTsProcessing
        }
        if (useNativeTsProcessing) {
            setupCaptionListeners(tsFactory)
        }
        val mediaSource = ProgressiveMediaSource.Factory(tsFactory).createMediaSource(MediaItem.fromUri(url))
        exoPlayer?.setMediaSource(mediaSource)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = wasPlaying
        tsSeekAdapter?.notifySeekApplied(newPositionOffsetMs)
        // シーク中ずっと再生中だった場合のみ、確定後にコントロールオーバーレイを閉じる
        // (戻るボタン押下時と同じhideControlsOverlay()。一時停止中にシークしていた場合は
        // ブラウズ中とみなしオーバーレイを表示したままにする)
        if (wasPlaying) {
            hideControlsOverlay(true)
        }
    }

    private fun startHlsPlayback(actionId: Long, httpClient: OkHttpClient, mode: Int) {
        EpgStationV2.api?.startRecordedHlsStream(actionId, mode = mode)?.enqueue(object : Callback<HlsStream> {
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

    private fun startLiveHlsPlayback(channelId: Long, httpClient: OkHttpClient, mode: Int) {
        EpgStationV2.api?.startLiveHlsStream(channelId, mode = mode)?.enqueue(object : Callback<HlsStream> {
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
                        // デフォルトはリトライ間隔だけでなく最大リトライ回数も3回に制限されており、
                        // 上のgetRetryDelayMsForで間隔を伸ばしても3回で致命的エラーになってしまう。
                        // ウォームアップ中の待機時間(最大40秒)を実際に確保するため回数上限も揃える。
                        override fun getMinimumLoadableRetryCount(dataType: Int): Int {
                            return LIVE_WARMUP_RETRY_COUNT
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
        // シーク(MediaSource再構築)のたびに呼ばれるため、ハンドルは使い回す
        if (captionHandle == 0L) {
            captionHandle = AribCaptionFilter.create(1920, 1080, AribCaptionFilter.TYPE_CAPTION)
        }
        if (superimposeHandle == 0L) {
            superimposeHandle = AribCaptionFilter.create(1920, 1080, AribCaptionFilter.TYPE_SUPERIMPOSE)
        }

        tsFactory.captionPesListener = PesCallback { ptsMs, pesPayload ->
            postCaptionCallback {
                val h = captionHandle
                if (h == 0L || !captionEnabled) return@postCaptionCallback
                if (AribCaptionFilter.decode(h, ptsMs, pesPayload, 0, pesPayload.size)) {
                    scheduleWithBufferDelay { scheduleCaptionRender(ptsMs) }
                }
            }
        }

        tsFactory.superimposePesListener = PesCallback { ptsMs, pesPayload ->
            postCaptionCallback {
                val h = superimposeHandle
                if (h == 0L || !superimposeEnabled) return@postCaptionCallback
                if (AribCaptionFilter.decode(h, ptsMs, pesPayload, 0, pesPayload.size)) {
                    scheduleWithBufferDelay { scheduleSuperimposeRender(ptsMs) }
                }
            }
        }
    }

    /** 字幕関連のHandlerコールバックをcaptionCallbackToken付きで投函する(シーク時に選択的キャンセルするため)。 */
    private fun postCaptionCallback(delayMs: Long = 0L, action: () -> Unit) {
        mainHandler.postAtTime(action, captionCallbackToken, SystemClock.uptimeMillis() + delayMs)
    }

    private fun scheduleWithBufferDelay(action: () -> Unit) {
        val p = exoPlayer
        val delayMs = if (p != null) {
            (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
        } else 0L
        if (delayMs > 50) {
            postCaptionCallback(delayMs, action)
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
        showQuickToast(getString(msg))
    }

    fun toggleSuperimpose() {
        superimposeEnabled = !superimposeEnabled
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit().putBoolean(PREF_SUPERIMPOSE_ENABLED, superimposeEnabled).apply()
        if (!superimposeEnabled) overlayView?.clearSuperimpose()
        val msg = if (superimposeEnabled) R.string.superimpose_on else R.string.superimpose_off
        showQuickToast(getString(msg))
    }

    fun toggleAudioTrack() {
        if (!hasSubAudio) {
            showQuickToast(getString(R.string.no_sub_audio))
            return
        }
        preferSubAudio = !preferSubAudio
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit().putBoolean(PREF_SUB_AUDIO, preferSubAudio).apply()
        selectAudioTrack(if (preferSubAudio) 1 else 0)
        val msg = if (preferSubAudio) R.string.audio_sub else R.string.audio_main
        showQuickToast(getString(msg))
    }

    /** アイコンで状態が分かるトグルボタン向けに、通常のToastより短く表示して消す */
    fun showQuickToast(message: String) {
        val toast = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT)
        toast.show()
        Handler(Looper.getMainLooper()).postDelayed({ toast.cancel() }, QUICK_TOAST_DURATION_MS)
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
        tsProbeExecutor.shutdownNow()
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
        // 録画TSシークバーの目標刻み間隔。実測durationからこの間隔に収まるよう
        // getSeekPositions()の点数を逆算する(各点は起動時にプローブ済みではなく、
        // duration/head/tailからの計算のみで求める——シーク確定時に1点だけ補正する)。
        private const val SEEK_POINT_INTERVAL_MS = 15_000L
        // シーク点数(=getSeekPositions()の配列長)の安全上限。点数自体はプロービング
        // コストを伴わないが、配列が際限なく肥大化しないための歯止め。
        // これを超える長さの録画は刻み間隔がSEEK_POINT_INTERVAL_MSより広がる。
        private const val SEEK_POINT_COUNT_MAX = 400
        // [Phase2調査・一時定数] サムネイル向けtsreadex正規化テストで先頭から読む生バイト数。
        // 高ビットレート録画でも数秒分(PAT/PMT複数回・キーフレーム含む)を確保できるよう余裕を持たせる。
        private const val THUMBNAIL_PROBE_RAW_BYTES = 8L * 1024 * 1024
        // ライブHLSウォームアップ中のリトライ回数・間隔（20回 x 2秒 = 最大40秒程度待つ）
        private const val LIVE_WARMUP_RETRY_COUNT = 20
        private const val LIVE_WARMUP_RETRY_DELAY_MS = 2_000L
        private const val PREF_CAPTION_ENABLED = "pref_caption_enabled"
        private const val PREF_SUPERIMPOSE_ENABLED = "pref_superimpose_enabled"
        private const val PREF_SUB_AUDIO = "pref_sub_audio"
        private const val QUICK_TOAST_DURATION_MS = 1000L

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

    private class TwoStateAction(
        id: Int,
        context: Context?,
        offDrawableRes: Int,
        onDrawableRes: Int,
        offLabel: String,
        onLabel: String,
    ) : PlaybackControlsRow.MultiAction(id) {
        init {
            setDrawables(arrayOf(context?.getDrawable(offDrawableRes), context?.getDrawable(onDrawableRes)))
            setLabels(arrayOf(offLabel, onLabel))
        }

        companion object {
            const val INDEX_OFF = 0
            const val INDEX_ON = 1
        }
    }

    class MyPlaybackTransportControlGlue(
        context: Context?,
        impl: PlayerAdapter,
        // ARIB字幕/デュアルモノ副音声のUI(CC/SI/音声切替ボタン)を出すかどうか。
        // TSかどうかではなく、tsreadexネイティブ処理を実際に使っているかで決める。
        private val useNativeTsProcessing: Boolean,
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

        private val superimposeAction = TwoStateAction(
            ACTION_ID_SUPERIMPOSE.toInt(),
            getContext(),
            R.drawable.ic_action_superimpose_off,
            R.drawable.ic_action_superimpose_on,
            "SI:OFF",
            "SI:ON"
        ).apply {
            index = if (superimposeEnabled) TwoStateAction.INDEX_ON else TwoStateAction.INDEX_OFF
        }

        private val audioAction = TwoStateAction(
            ACTION_ID_AUDIO.toInt(),
            getContext(),
            R.drawable.ic_action_audio_track_main,
            R.drawable.ic_action_audio_track_sub,
            "Main",
            "Sub"
        ).apply {
            index = if (preferSubAudio) TwoStateAction.INDEX_ON else TwoStateAction.INDEX_OFF
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
            if (useNativeTsProcessing) {
                primaryActionsAdapter.add(ccAction)
                primaryActionsAdapter.add(superimposeAction)
                primaryActionsAdapter.add(audioAction)
            }
            if (isLive) {
                primaryActionsAdapter.add(recordAction)
                primaryActionsAdapter.add(infoAction)
            }
            if (useNativeTsProcessing || isLive) {
                primaryActions = primaryActionsAdapter
            }
        }

        fun updateAudioActionState(enabled: Boolean) {
            audioActionEnabled = enabled
            if (enabled) {
                // Re-apply the current index to restore the icon/label overwritten by the disabled state below.
                audioAction.index = audioAction.index
            } else {
                audioAction.label1 = "---"
            }
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
                    superimposeAction.index = if (enabled) TwoStateAction.INDEX_ON else TwoStateAction.INDEX_OFF
                    primaryActions?.let { adapter ->
                        val idx = adapter.indexOf(superimposeAction)
                        if (idx >= 0) adapter.notifyArrayItemRangeChanged(idx, 1)
                    }
                }
                audioAction -> {
                    if (!audioActionEnabled) {
                        playbackFragment?.showQuickToast(context?.getString(R.string.no_sub_audio) ?: "")
                        return
                    }
                    playbackFragment?.toggleAudioTrack()
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context!!)
                    val isSub = prefs.getBoolean(PREF_SUB_AUDIO, false)
                    audioAction.index = if (isSub) TwoStateAction.INDEX_ON else TwoStateAction.INDEX_OFF
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
