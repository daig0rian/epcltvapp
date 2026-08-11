package com.daigorian.epcltvapp

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TypefaceSpan
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
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
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.preference.PreferenceManager
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import com.daigorian.epcltvapp.epgstationcaller.GetRecordedResponse
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.ApiErrorV2
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.HlsStream
import com.daigorian.epcltvapp.epgstationv2caller.ManualReserveOption
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import com.daigorian.epcltvapp.epgstationv2caller.Records
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
    // ExoPlayerのtextトラック(Cue)描画先。ARIB字幕用のoverlayViewとは供給元が異なるため別ビュー。
    private var subtitleView: SubtitleView? = null

    // ARIB caption handles
    private var captionHandle: Long = 0
    private var superimposeHandle: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    // 字幕PES処理・遅延レンダリングのHandlerコールバックに付けるトークン。
    // シーク時にmainHandler全体ではなくこれらだけを選択的にキャンセルするために使う
    // (mainHandlerは他の目的にも使い回している共有インスタンスのため)。
    private val captionCallbackToken = Any()

    // デコード済みだがまだ表示位置に達していない字幕/文字スーパー。
    private val pendingCaptions = ArrayDeque<PendingCaption>()
    private val pendingSuperimposes = ArrayDeque<PendingCaption>()
    // 表示中の字幕/文字スーパーを消去する再生位置(表示していなければ C.TIME_UNSET)。
    private var captionExpiryPositionMs = C.TIME_UNSET
    private var superimposeExpiryPositionMs = C.TIME_UNSET
    private var captionTickerScheduled = false

    /** デコード済み字幕1件と、それを表示すべき再生位置。 */
    private class PendingCaption(val targetPositionMs: Long, val ptsMs: Long)

    // Persisted toggle states
    private var captionEnabled = false
    private var superimposeEnabled = false
    private var preferSubAudio = false

    // Audio track state
    private val audioGroups = mutableListOf<Tracks.Group>()
    private var hasSubAudio = false

    // Text track state (加工済みTS/エンコード済み動画に埋め込まれた字幕)。
    // ARIB字幕は自前デコード(libaribcaption)なのでここには現れない——tsreadexを通す入力では
    // そもそもARIB字幕以外が存在せず、通さない入力ではARIB字幕をExoPlayerが認識しないため、
    // 両者が同時に埋まることはない。
    private val textGroups = mutableListOf<Tracks.Group>()
    private var hasTextTrack = false

    // Content type
    private var isTsContent = false
    // ARIB字幕/デュアルモノ副音声を扱うtsreadexネイティブフィルタを使うかどうか。
    // 生TS(isRawTs)のサブセット。判定はonCreate参照。
    private var useNativeTsProcessing = false

    // TS seek support (録画オリジナルTSのみ)
    private var tsSeekAdapter: TsSeekPlayerAdapter? = null
    // TS以外(エンコード済み直接再生・HLS)のシーク対応
    private var seekableAdapter: SeekableLeanbackPlayerAdapter? = null
    private var tsSeekUrl: String? = null
    private var tsSeekHttpClient: OkHttpClient? = null
    private var tsSeekDataProvider: TsSeekDataProvider? = null
    private val tsProbeExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor()

    // TS追いかけ再生（Issue #42）: Details画面表示時点で収録中だったTSのみ有効化する。
    // 収録終了を確認した時点でfalseに固定し、以後は再プローブ/再確認を行わない。
    private var tsCatchUpActive = false
    private var tsCatchUpRecordedProgramId: Long? = null // EPGStation v1
    private var tsCatchUpRecordedItemId: Long? = null // EPGStation v2
    // probeHeadの結果は再生中不変のため一度だけ保持し、再プローブのたびに使い回す。
    private var tsHeadPoint: TsProbe.HeadProbeResult? = null

    // レジューム再生
    // 前回停止位置の保存先キー。レジューム対象外(ライブ・HLS追いかけ再生)ならnull。
    private var resumePositionKey: String? = null
    // 起動時に読み出した前回停止位置。0なら記録なし(=何もしない)。
    private var savedResumePositionMs = 0L
    // 設定「レジューム再生」の値(毎回確認 / 最初から / 前回停止位置から)。
    private var resumeMode: String? = null
    // 録画オリジナルTSでシーク点テーブルの完成を待っているレジューム要求。
    private var pendingTsResumePositionMs: Long? = null
    // 再生位置・総時間の取得口。TS/非TSで実装が分かれる(TsSeekPlayerAdapter参照)ため
    // 直接ExoPlayerを見ず、Leanbackへ見せているのと同じ値をここから取る。
    private var playerAdapter: PlayerAdapter? = null

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
        // レジューム再生: 対象は「録画済みファイルを最初から通して見る」再生だけ。
        // ライブと HLS 追いかけ再生(収録中のものを今のところまで見る)は前回位置に意味がない。
        if (!isAnyLive && !isHls) {
            resumePositionKey = buildResumePositionKey(recordedProgram?.id, recordedItem?.id, actionId)
            // 位置の記録自体は設定によらず常に続ける(「毎回確認」に戻したときすぐ使えるように)。
            // 設定が決めるのは、記録された位置をどう使うか(確認する/使わない/黙って飛ぶ)だけ。
            savedResumePositionMs = resumePositionKey
                ?.let { PlaybackPositionStore.load(requireContext(), it) } ?: 0L
            resumeMode = prefs.getString(
                getString(R.string.pref_key_resume_playback_mode),
                getString(R.string.pref_val_resume_mode_default)
            )
        }

        val liveM2tsProfiles = EpgStationV2.streamConfig?.live?.ts?.m2ts.orEmpty()
        val liveMpegTsMode = EpgStationV2.resolveM2tsProfileIndex(
            prefs.getString(getString(R.string.pref_key_live_mpegts_profile), ""),
            liveM2tsProfiles
        )

        // isTsContent は「TSコンテナかどうか」のみを表すフラグで、TsReadexDataSourceでのラップと
        // シーク方式の判定に使う。ネイティブ処理(tsreadex/ARIB字幕/デュアルモノ副音声)を使うかは
        // useNativeTsProcessing として別管理する——TS向けシーク機能はネイティブ処理を使わなくても
        // (生バイトを直接読むだけなので)動作するため、両者は独立している。
        val isRecordedTs = activity?.intent?.getBooleanExtra(DetailsActivity.IS_TS_CONTENT, false) ?: false
        isTsContent = isRecordedTs || isLiveMpegTs

        // 生TS = 放送波そのもの。tsreadexのservicefilterはPMTを作り直し、
        // video/audio1/audio2/ARIB字幕/ARIB文字スーパーの5本以外のストリームを捨てるため、
        // 通してよいのは「ARIB字幕以外が付く余地のない」入力に限られる。
        //   生TS  : 録画オリジナルTS(追いかけ再生含む)・ライブTSの無変換プロファイル
        //   加工済: エンコード済み動画・ライブTSの変換ありプロファイル
        //           (音声が最適化されていたりARIB字幕が他形式へ変換されている可能性があり、
        //            tsreadexを通すとそれらが消えてしまう)
        // streamConfig未取得時はisUnconvertedを判定できないため、通さない側に倒す。
        val isRawTs = isRecordedTs ||
                (isLiveMpegTs && liveM2tsProfiles.getOrNull(liveMpegTsMode)?.isUnconverted == true)

        // ライブmpegts直送は#33のクラッシュ疑いにより長らくネイティブTS処理を強制バイパス
        // していたが、Issue #34でユーザー切り替え可能な設定にした。#33が実機で未解決のため、
        // デフォルトはOFF（従来通りバイパス）とし、必要な人だけONにする。
        val nativeTsProcessingPref = prefs.getBoolean(getString(R.string.pref_key_native_ts_processing), false)
        useNativeTsProcessing = isRawTs && nativeTsProcessingPref

        // Build ExoPlayer
        trackSelector = DefaultTrackSelector(requireContext())
        // TS・ライブは素早い再生開始と低遅延のためバッファを小さく保つ。
        //
        // 一方、録画済みのエンコード済み動画/HLSでは大きめに取る必要がある。EPGStationが
        // ffmpegで出力するMP4は字幕トラックの多重化位置が映像から大きくずれることがあり
        // (sparseなストリームはmax_interleave_deltaの影響で数十秒ぶんずれた位置に書かれる)、
        // 先読みが小さいと字幕サンプルの到着だけが遅れて「字幕が止まった後に一気に流れて
        // 追いつく」挙動になる。実測ログでは映像・音声は正常に進みバッファも健全なまま、
        // 字幕だけ34秒間途切れてから0.5秒で15件まとめて届いていた。
        // ここはmedia3のDefaultLoadControlの既定値(50秒)に任せる。
        val loadControl = DefaultLoadControl.Builder()
            .apply {
                if (isTsContent || isAnyLive) setBufferDurationsMs(1_000, 8_000, 500, 1_000)
            }
            .build()

        exoPlayer = ExoPlayer.Builder(requireContext(), DefaultRenderersFactory(requireContext()))
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build()

        exoPlayer!!.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "onPlaybackStateChanged: $playbackState")
                if (playbackState == Player.STATE_ENDED && tsCatchUpActive) {
                    // EOF手前の安全マージン分だけ戻した位置へ「今と同時刻へのシーク」を行う。
                    // これがrestartTsPlaybackAt()を経由して開き直しになり、その末尾で
                    // 収録状況の再確認とシークバー更新が再度走る。
                    performTsCatchUpSeek()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "onPlayerError: $error")
            }
            override fun onTracksChanged(tracks: Tracks) {
                val newAudioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val newTextGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                Log.d(TAG, "onTracksChanged: ${newAudioGroups.size} audio group(s), ${newTextGroups.size} text group(s)")
                audioGroups.clear()
                audioGroups.addAll(newAudioGroups)
                textGroups.clear()
                textGroups.addAll(newTextGroups)
                val hadSubAudio = hasSubAudio
                val hadTextTrack = hasTextTrack
                hasSubAudio = newAudioGroups.size >= 2
                hasTextTrack = newTextGroups.isNotEmpty()
                if (hasSubAudio != hadSubAudio || hasTextTrack != hadTextTrack) {
                    mTransportControlGlue.updateTrackActions(hasSubtitleSource(), hasSubAudio)
                }
                if (hasSubAudio && preferSubAudio) {
                    selectAudioTrack(1)
                }
                // 永続化された字幕ON/OFFの状態を、トラックが見え始めたこの時点で反映する。
                if (hasTextTrack != hadTextTrack) {
                    applyTextTrackSelection()
                }
            }
            override fun onCues(cueGroup: CueGroup) {
                subtitleView?.setCues(cueGroup.cues.map { adjustCue(it) })
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

        // textトラックが見つかった瞬間にDefaultTrackSelectorが既定で選んでしまい、字幕OFFのはずが
        // 一瞬表示されるのを防ぐため、再生開始前に現在の設定を一度反映しておく。
        applyTextTrackSelection()

        // Leanback glue
        // 録画オリジナルTS再生時は、TsReadexDataSource が duration を C.TIME_UNSET として
        // 隠しているため、TsProbe の実測値とシーク時のオフセットを自前管理できる
        // TsSeekPlayerAdapter を使う（LeanbackPlayerAdapter は final のため継承不可）。
        val playerAdapter: PlayerAdapter = if (isTsContent && !isAnyLive) {
            TsSeekPlayerAdapter(exoPlayer!!, UPDATE_PERIOD_MS) { targetMs ->
                performTsSeek(targetMs)
            }.also { tsSeekAdapter = it }
        } else {
            SeekableLeanbackPlayerAdapter(requireContext(), exoPlayer!!, UPDATE_PERIOD_MS) {
                // ここで同期的に閉じてはいけない。Leanbackは同じメッセージ内で
                // このseekTo()の直後に setSeekMode(false) → showControlsOverlay(true) を
                // 実行するため即座に開き直される。しかも hideControlsOverlay() は
                // stopFadeTimer() を伴うので、tickle()が仕掛けたフェードタイマーごと
                // 打ち消してしまい、かえって開きっぱなしに固定される。
                // そのため post して、Leanback側の一連の処理が終わった後に閉じる
                // (TS経路が非同期プローブを挟むことで結果的にこうなっているのと同じ。
                //  restartTsPlaybackAt参照)。
                mainHandler.post {
                    // シーク中ずっと再生中だった場合のみ閉じる(一時停止中のシークは
                    // ブラウズ中とみなしオーバーレイを表示したままにする)
                    if (isAdded && exoPlayer?.playWhenReady == true) hideControlsOverlay(true)
                }
            }.also { seekableAdapter = it }
        }
        this.playerAdapter = playerAdapter
        val glueHost = VideoSupportFragmentGlueHost(this@PlaybackVideoFragment)

        mTransportControlGlue = MyPlaybackTransportControlGlue(
            activity, playerAdapter, useNativeTsProcessing, isAnyLive,
            captionEnabled, superimposeEnabled, preferSubAudio,
            hasSubtitleSource(), hasSubAudio
        )
        mTransportControlGlue.host = glueHost
        mTransportControlGlue.title = recordedProgram?.name ?: recordedItem?.name ?: liveChannelName
        mTransportControlGlue.subtitle = recordedProgram?.description ?: recordedItem?.description
        // TS以外はExoPlayerがdurationをネイティブに把握しているため、TSのような事前プロービング
        // 待ちなしで直接シークプロバイダを組める(ExoPlayerネイティブのseekTo(ms)がそのまま使える)。
        // これによりLeanbackはDpadでのスクラブ中に実シークを都度呼ばず確定時に1回だけ呼ぶようになり、
        // かつシーク開始時の自動pauseもTSと同様に打ち消せる(SeekableLeanbackPlayerAdapter参照)。
        seekableAdapter?.let { adapter ->
            mTransportControlGlue.setSeekProvider(
                DurationSeekDataProvider(exoPlayer!!) { adapter.resumePlaybackIfWasPlaying() }
            )
        }
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
            tsCatchUpActive = recordedProgram?.recording == true || recordedItem?.isRecording == true
            tsCatchUpRecordedProgramId = recordedProgram?.id
            tsCatchUpRecordedItemId = recordedItem?.id
            startTsProbing(cleanUrl, okHttpClient)
        }
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
        // ExoPlayerのtextトラック描画先。ネイティブTS処理中はtextトラック自体が現れないので
        // 実質使われないが、条件分岐を増やさないため常に置く(Cueが来なければ何も描画しない)。
        subtitleView = SubtitleView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            applySubtitleStyle(this)
        }
        root?.addView(subtitleView, if (overlayView != null) 2 else 1)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setControlsOverlayAutoHideEnabled(true)
        if (isLiveMpegTs) {
            hideSeekBar(view)
        }
        // 再生成時は DialogFragment 自身が復元されるため出し直さない。
        if (savedResumePositionMs <= 0 || savedInstanceState != null) return
        when (resumeMode) {
            // 再生はすでに先頭から始まっている。その上に半透明で確認を重ねるので、
            // 何も選ばずに放っておいてもそのまま頭から見続けられる。
            getString(R.string.pref_val_resume_mode_ask) ->
                ResumePlaybackDialogFragment()
                    .show(childFragmentManager, ResumePlaybackDialogFragment.TAG)
            getString(R.string.pref_val_resume_mode_resume) ->
                seekToResumePosition(savedResumePositionMs)
            // 「最初から」は記録があっても使わない(=何もしない)。
        }
    }

    /**
     * [ResumePlaybackDialogFragment] の選択結果。
     *
     * [resume] が false(=「このまま」)のときは何もしない。前回位置の記録もそのまま残す
     * ——ダイアログは無操作でも5秒で消えるので、記録を消してまで次回の確認を止める必要がない。
     */
    fun onResumeChoice(resume: Boolean, dontAskAgain: Boolean) {
        if (dontAskAgain) {
            val mode = if (resume) {
                getString(R.string.pref_val_resume_mode_resume)
            } else {
                getString(R.string.pref_val_resume_mode_beginning)
            }
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putString(getString(R.string.pref_key_resume_playback_mode), mode).apply()
            Log.d(TAG, "onResumeChoice: resume mode changed to $mode")
        }
        if (resume) seekToResumePosition(savedResumePositionMs)
    }

    private fun seekToResumePosition(positionMs: Long) {
        if (tsSeekAdapter != null) {
            // 録画オリジナルTSのシークはTsProbeのシーク点テーブル待ち。
            // 完成前ならここでは積んでおき、refreshTailAndSeekBar の完了時に実行する。
            if (tsSeekDataProvider != null) {
                performTsSeek(positionMs)
            } else {
                pendingTsResumePositionMs = positionMs
                Log.d(TAG, "seekToResumePosition: deferred until seek table is ready ($positionMs ms)")
            }
            return
        }
        val player = exoPlayer ?: return
        val durationMs = player.duration
        // ファイルが差し替わっている等で記録が総時間を超えていた場合の保険。
        val target = if (durationMs != C.TIME_UNSET && durationMs > 0) {
            positionMs.coerceAtMost(durationMs - 1)
        } else {
            positionMs
        }
        player.seekTo(target)
        Log.d(TAG, "seekToResumePosition: seekTo($target)")
    }

    /**
     * 今の再生位置をレジューム再生用に記録する(画面を離れるとき)。
     *
     * - 見始めてすぐ(=[RESUME_MIN_POSITION_MS]未満)は何もしない。**前回の記録も消さない**
     *   ——確認ダイアログを見て何も選ばずに戻っただけ、というケースで記録を失わないため。
     * - 終端付近まで見ていたら記録を消す(最後まで見た動画に次回また確認が出るのは煩わしい)。
     */
    private fun savePlaybackPosition() {
        val key = resumePositionKey ?: return
        val adapter = playerAdapter ?: return
        val positionMs = adapter.currentPosition
        if (positionMs < RESUME_MIN_POSITION_MS) return
        val durationMs = adapter.duration
        if (durationMs > 0 && positionMs >= durationMs - RESUME_END_MARGIN_MS) {
            PlaybackPositionStore.remove(requireContext(), key)
            return
        }
        PlaybackPositionStore.save(requireContext(), key, positionMs)
    }

    /**
     * 前回停止位置の保存先キー。動画ファイル1本を一意に指す必要があるため、
     * 番組IDだけでなく再生対象(TS/エンコード済みの別、v2ならvideoFileのID)まで含める。
     */
    private fun buildResumePositionKey(programId: Long?, itemId: Long?, actionId: Long): String? = when {
        // EPGStation v1: actionId は ACTION_WATCH_ORIGINAL_TS または encodedId
        programId != null -> "v1:$programId:$actionId"
        // EPGStation v2: actionId は videoFile の ID
        itemId != null -> "v2:$itemId:$actionId"
        else -> null
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
            tsHeadPoint = head
            refreshTailAndSeekBar(url, client, head)
        }
        // ①(再生開始時点): 追いかけ再生対象なら収録状況を確認しておく。
        if (tsCatchUpActive) refreshCatchUpRecordingStatus()
    }

    /**
     * tail(ファイル終端)を再プローブしシークバーへ反映する。tsProbeExecutor上の
     * バックグラウンドスレッドから呼ぶ前提(fetchFileSize/probeTailがブロッキングI/Oのため)。
     * 初回再生開始時(startTsProbing)・シーク完了時(restartTsPlaybackAt)の両方から呼ばれる
     * ——追いかけ再生(Issue #42)はこの「毎回tailを取り直す」性質を利用して、シークバーの
     * 終端を最新の実データに追従させる。
     */
    private fun refreshTailAndSeekBar(url: String, client: OkHttpClient, head: TsProbe.HeadProbeResult) {
        val fileSize = TsProbe.fetchFileSize(url, client)
        val tail = if (fileSize != null) TsProbe.probeTail(url, client, fileSize, head.pcrPid) else null
        if (fileSize == null || tail == null || tail.timeMs - head.firstPcr.timeMs <= 0) {
            Log.w(TAG, "refreshTailAndSeekBar: probe failed or invalid duration")
            return
        }
        // head/tailの2点だけでdurationとシークを即座に有効化する(再生開始を待たせない)。
        // 各シーク位置の正確なバイト位置はここでは求めず、確定時に1回だけ軽量プローブして
        // 補正する(performTsSeek参照)。
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
            Log.d(TAG, "refreshTailAndSeekBar: seek ready (${provider.seekPositionCount} points), durationMs=${provider.durationMs}")
            // シーク可能になるのを待っていたレジューム要求をここで実行する。
            pendingTsResumePositionMs?.let { positionMs ->
                pendingTsResumePositionMs = null
                performTsSeek(positionMs)
            }
        }
    }

    /**
     * 収録中TS追いかけ再生（Issue #42）: 現在の収録状況をEPGStation APIへ再確認する。
     * v1/v2とも単一IDで収録状況を問い合わせるエンドポイントが存在しないため、「収録中一覧」を
     * 取得して対象IDが含まれるかで判定する。収録終了を確認したら tsCatchUpActive を false に
     * 固定し、以後は呼び出し元(startTsProbing/restartTsPlaybackAt)がこの再確認自体を呼ばなくなる。
     * 問い合わせ失敗時は現状維持とし、次回のシーク/再オープン時に再試行する。
     */
    private fun refreshCatchUpRecordingStatus() {
        val programId = tsCatchUpRecordedProgramId
        val itemId = tsCatchUpRecordedItemId
        if (programId != null) {
            EpgStation.api?.getRecorded(recording = true)?.enqueue(object : Callback<GetRecordedResponse> {
                override fun onResponse(call: Call<GetRecordedResponse>, response: Response<GetRecordedResponse>) {
                    if (response.isSuccessful) {
                        tsCatchUpActive = response.body()?.recorded?.any { it.id == programId } == true
                    }
                }
                override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                    Log.w(TAG, "refreshCatchUpRecordingStatus(v1) failed: ${t.message}")
                }
            })
        } else if (itemId != null) {
            EpgStationV2.api?.getRecording()?.enqueue(object : Callback<Records> {
                override fun onResponse(call: Call<Records>, response: Response<Records>) {
                    if (response.isSuccessful) {
                        tsCatchUpActive = response.body()?.records?.any { it.id == itemId } == true
                    }
                }
                override fun onFailure(call: Call<Records>, t: Throwable) {
                    Log.w(TAG, "refreshCatchUpRecordingStatus(v2) failed: ${t.message}")
                }
            })
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
        val clampedTarget = targetPositionMs.coerceIn(0, provider.durationMs)
        val guessByteOffset = provider.estimateByteOffset(clampedTarget)
        seekToByteOffsetGuess(guessByteOffset, clampedTarget)
    }

    /**
     * 収録中TS追いかけ再生（Issue #42）: STATE_ENDED検知時に「今の終端」へ疑似シークする。
     * [performTsSeek]とは異なり、シークバー用の安全マージン(maxSeekableMs、15秒)ではなく
     * [TS_CATCHUP_SAFETY_MARGIN_MS](1秒)だけ手前を狙う
     * ([TsSeekDataProvider.estimateByteOffsetNearTail]参照)。
     */
    private fun performTsCatchUpSeek() {
        val provider = tsSeekDataProvider ?: return
        val targetPositionMs = (provider.durationMs - TS_CATCHUP_SAFETY_MARGIN_MS).coerceAtLeast(0)
        val guessByteOffset = provider.estimateByteOffsetNearTail(TS_CATCHUP_SAFETY_MARGIN_MS)
        seekToByteOffsetGuess(guessByteOffset, targetPositionMs)
    }

    /**
     * 概算バイト位置を1回だけ軽量プローブして実際の位置に補正し、MediaSourceを開き直す。
     * [performTsSeek]（通常のシーク確定）・[performTsCatchUpSeek]（追いかけ再生の再オープン）の
     * 共通の後段処理。
     */
    private fun seekToByteOffsetGuess(guessByteOffset: Long, targetPositionMs: Long) {
        val provider = tsSeekDataProvider ?: return
        val url = tsSeekUrl ?: return
        val client = tsSeekHttpClient ?: return
        tsSeekAdapter?.notifySeekPending(targetPositionMs)
        tsProbeExecutor.execute {
            val refined = TsProbe.refineSeekPoint(url, client, provider.fileSize, provider.pcrPid, guessByteOffset)
            mainHandler.post {
                if (!isAdded) return@post
                if (refined != null) {
                    restartTsPlaybackAt(refined.byteOffset, provider.toRelativeMs(refined.timeMs))
                } else {
                    // 補正プローブに失敗した場合は概算値のまま着地する(何もしないよりまし)
                    Log.w(TAG, "seekToByteOffsetGuess: refine probe failed, falling back to estimate")
                    restartTsPlaybackAt(guessByteOffset, targetPositionMs)
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
        captionTickerScheduled = false
        resetCaptionScheduling()
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
        // ①(シーク完了時点): MediaSourceの再構築(=このメソッド)は追いかけ再生対象TSにとって
        // 「今この瞬間の実データで開き直された」タイミングそのものなので、tailを再プローブして
        // シークバーを追従させ、収録状況も再確認する(視聴中に収録が終わっている可能性があるため)。
        val head = tsHeadPoint
        if (tsCatchUpActive && head != null) {
            tsProbeExecutor.execute { refreshTailAndSeekBar(url, client, head) }
            refreshCatchUpRecordingStatus()
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
                    enqueuePendingCaption(pendingCaptions, ptsMs)
                }
            }
        }

        tsFactory.superimposePesListener = PesCallback { ptsMs, pesPayload ->
            postCaptionCallback {
                val h = superimposeHandle
                if (h == 0L || !superimposeEnabled) return@postCaptionCallback
                if (AribCaptionFilter.decode(h, ptsMs, pesPayload, 0, pesPayload.size)) {
                    enqueuePendingCaption(pendingSuperimposes, ptsMs)
                }
            }
        }
    }

    /** 字幕関連のHandlerコールバックをcaptionCallbackToken付きで投函する(シーク時に選択的キャンセルするため)。 */
    private fun postCaptionCallback(delayMs: Long = 0L, action: () -> Unit) {
        mainHandler.postAtTime(action, captionCallbackToken, SystemClock.uptimeMillis() + delayMs)
    }

    /**
     * デコード済み字幕を「表示すべき再生位置」付きで待ち行列に積む。
     *
     * 字幕PESはExoPlayerがデータをロードした時点(=再生位置より先)で解析されるため、
     * その字幕が実際に表示されるべき再生位置は解析時点のバッファ末尾とみなせる。
     */
    private fun enqueuePendingCaption(queue: ArrayDeque<PendingCaption>, ptsMs: Long) {
        val p = exoPlayer ?: return
        val targetPositionMs = maxOf(p.bufferedPosition, p.currentPosition)
        queue.addLast(PendingCaption(targetPositionMs, ptsMs))
        ensureCaptionTicker()
    }

    /**
     * 字幕の表示/消去は壁時計ではなく再生位置で判定するため、定期的に再生位置を見て
     * 期限が来たものを処理する。一時停止中は再生位置が進まないので、待機中の字幕が
     * 先走って表示されることも、表示中の字幕が勝手に消えることもない。
     */
    private fun ensureCaptionTicker() {
        if (captionTickerScheduled) return
        captionTickerScheduled = true
        postCaptionCallback(CAPTION_TICK_MS) { onCaptionTick() }
    }

    private fun onCaptionTick() {
        captionTickerScheduled = false
        val positionMs = exoPlayer?.currentPosition ?: return

        captionExpiryPositionMs = updateCaptionLayer(
            queue = pendingCaptions,
            positionMs = positionMs,
            handle = captionHandle,
            enabled = captionEnabled,
            expiryPositionMs = captionExpiryPositionMs,
            maxDurationMs = CAPTION_MAX_DURATION_MS,
            show = { images -> overlayView?.showCaptions(images) },
            clear = { overlayView?.clearCaptions() },
        )

        superimposeExpiryPositionMs = updateCaptionLayer(
            queue = pendingSuperimposes,
            positionMs = positionMs,
            handle = superimposeHandle,
            enabled = superimposeEnabled,
            expiryPositionMs = superimposeExpiryPositionMs,
            maxDurationMs = SUPERIMPOSE_MAX_DURATION_MS,
            show = { images -> overlayView?.showSuperimpose(images) },
            clear = { overlayView?.clearSuperimpose() },
        )

        if (pendingCaptions.isNotEmpty() || pendingSuperimposes.isNotEmpty() ||
            captionExpiryPositionMs != C.TIME_UNSET || superimposeExpiryPositionMs != C.TIME_UNSET
        ) {
            ensureCaptionTicker()
        }
    }

    /**
     * 字幕・文字スーパーの1レイヤ分の表示状態を現在の再生位置に合わせて更新し、
     * 更新後の消去予定位置(表示中でなければ [C.TIME_UNSET])を返す。
     */
    private fun updateCaptionLayer(
        queue: ArrayDeque<PendingCaption>,
        positionMs: Long,
        handle: Long,
        enabled: Boolean,
        expiryPositionMs: Long,
        maxDurationMs: Long,
        show: (Array<CaptionImage>) -> Unit,
        clear: () -> Unit,
    ): Long {
        // 表示位置に達したものだけを取り出す。複数溜まっている場合(長時間のスタール後など)は
        // 途中のものを一瞬ずつ描画しても意味がないので最後の1つだけを表示する。
        var due: PendingCaption? = null
        while (queue.isNotEmpty() && queue.first().targetPositionMs <= positionMs) {
            due = queue.removeFirst()
        }

        if (due != null && handle != 0L && enabled) {
            val images = AribCaptionFilter.render(handle, due.ptsMs)
            if (images.isNotEmpty()) {
                show(images)
                val durationMs = images[0].durationMs.coerceIn(CAPTION_MIN_DURATION_MS, maxDurationMs)
                return positionMs + durationMs
            }
        }

        if (expiryPositionMs != C.TIME_UNSET && positionMs >= expiryPositionMs) {
            clear()
            return C.TIME_UNSET
        }
        return expiryPositionMs
    }

    /** 待機中・表示中の字幕をすべて破棄する(シーク時・字幕オフ時)。 */
    private fun resetCaptionScheduling() {
        pendingCaptions.clear()
        pendingSuperimposes.clear()
        captionExpiryPositionMs = C.TIME_UNSET
        superimposeExpiryPositionMs = C.TIME_UNSET
    }

    private fun selectAudioTrack(groupIndex: Int) {
        val player = exoPlayer ?: return
        if (groupIndex >= audioGroups.size) return
        val targetGroup = audioGroups[groupIndex].mediaTrackGroup
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(targetGroup, listOf(0)))
            .build()
        Log.d(TAG, "selectAudioTrack: groupIndex=$groupIndex")
    }

    /**
     * [SubtitleView] の字幕スタイルを決める。
     *
     * OSのユーザー補助の字幕設定は参照せず、常に同じ見た目にする。
     * `setUserDefaultStyle()` を使うと、OS側で字幕が有効化されていないときに
     * [CaptionStyleCompat.DEFAULT]（白文字・**不透明な**黒背景・縁取りなし・既定フォント）へ
     * フォールバックして黒帯で映像が隠れる。TVでは無効なのが通常なので、そもそも見た目を
     * OS設定に委ねること自体をやめている(OS側の fontScale も効かなくなる)。
     *
     * 見た目はARIB字幕(libaribcaption)に揃える:
     *  - 下地は半透明の黒。ARIBはB24 CLUTの半透明パレットを使い、そのアルファは128で確定して
     *    いる(`b24_colors.cpp`。CLUTは不透明セットとアルファ128セットの二択で中間値がない)。
     *    libaribcaption側も `Canvas::ClearRect` → `alphablend::FillLine` が単純な上書きで、
     *    画面への合成までアルファが変わらないため、同じ128を指定すれば数値上は一致する。
     *  - 下地は window ではなく **background** に指定する。backgroundは
     *    `BackgroundColorSpan` として字面に密着して塗られ、ARIBが文字セル矩形をベタ塗りする
     *    のに近い。windowはブロック全体を1つの矩形で塗り、`SubtitlePainter.textPaddingX`
     *    (既定文字サイズの12.5%)の余白が左右につくため離れる。
     *  - 縁取りなし。ARIB字幕も縁取りを持たない(`force_stroke_text_` は既定false)。
     *
     * **Typefaceは指定しない(null)。** ここを日本語フォントで明示すると縦の下地が伸びる。
     * `SubtitlePainter` は `StaticLayout(..., includepad = true)` で行ボックスを組み、
     * `BackgroundColorSpan` はその行ボックス全体を塗る。行ボックスの高さは
     * **Paintの主フォントの縦メトリクス**で決まるため、実測でこれだけ差が出る:
     *
     * |                       | upem | hhea(asc/desc) | 行高      |
     * |-----------------------|------|----------------|-----------|
     * | Roboto(既定)          | 2048 | 1900 / -500    | 1.1719 em |
     * | NotoSansCJK-Regular   | 1000 | 1160 / -288    | 1.4480 em |
     *
     * 日本語のグリフはTypefaceを指定しなくてもフォールバックチェーンがCJKフォントから
     * 供給するので字形は変わらない。変わるのは行ボックスの高さ=下地の縦幅だけなので、
     * 指定しないのが正しい。
     *
     * なお `Tx3gParser` が付ける [TypefaceSpan] ("Serif") は明朝体かつセリフ体の
     * メトリクスを持ち込むため、[adjustCue] で必ず剥がすこと。
     */
    private fun applySubtitleStyle(view: SubtitleView) {
        view.setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                SUBTITLE_BACKGROUND_COLOR,
                // window は使わない(下地は background 側で塗る)
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_NONE,
                // EDGE_TYPE_NONE では参照されないが、コンストラクタが要求するので埋める
                Color.BLACK,
                null
            )
        )
        view.setFractionalTextSize(SUBTITLE_TEXT_SIZE_FRACTION)
    }

    /**
     * 字幕トラックが持ち込むスタイル・位置指定のうち、こちらの見た目指定を無効化して
     * しまうものを取り除く。
     *
     * EPGStationがffmpegで生成するMP4の字幕はtx3g(mov_text)で、`Tx3gParser` は
     *  - フォント名(ffmpegの既定は "Serif")を [TypefaceSpan] として本文に付ける。
     *    スパンはPaintのTypefaceより優先されるため、明朝体で描画されてしまう。
     *    さらにセリフ体の縦メトリクスが行ボックスに効くので下地の縦幅も変わる
     *  - 縦位置を必ずCueに設定する(既定 0.85)。このため [SubtitleView] の
     *    bottomPaddingFraction は一切効かない
     *
     * どちらも「字幕の内容」ではなくパーサの既定値なので上書きしてよい。
     * 縦位置は [SUBTITLE_LINE_FRACTION] に統一する。
     *
     * bitmapのCue(DVB字幕等)はそのまま通す——本文テキストを持たず、位置もストリームが
     * 意味を持って指定しているため。
     */
    private fun adjustCue(cue: Cue): Cue {
        if (cue.bitmap != null) return cue
        val builder = cue.buildUpon()
        val text = cue.text
        if (text is Spanned) {
            val typefaceSpans = text.getSpans(0, text.length, TypefaceSpan::class.java)
            if (typefaceSpans.isNotEmpty()) {
                val stripped = SpannableStringBuilder(text)
                for (span in typefaceSpans) stripped.removeSpan(span)
                builder.setText(stripped)
            }
        }
        // 文字サイズはCue側に指定しない。`Tx3gParser` はCueにtextSizeを設定しないため、
        // 未設定のままなら SubtitlePainter は SubtitleView の既定文字サイズをそのまま使う。
        return builder
            .setLine(SUBTITLE_LINE_FRACTION, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .build()
    }

    /**
     * 字幕を出せる供給元があるか。CCボタンを出すかどうかの判定に使う。
     * 供給元は2つあるが、[useNativeTsProcessing] が true なのは生TS(=ARIB字幕以外が
     * 存在しない入力)のときだけなので、実際には排他になる。
     */
    private fun hasSubtitleSource(): Boolean = useNativeTsProcessing || hasTextTrack

    /**
     * ExoPlayerのtextトラックを字幕ON/OFFに追従させる。
     *
     * DefaultTrackSelectorは既定では優先言語やforcedフラグを見てtextトラックを選ぶため、
     * 「ONなら必ず出る」状態にするには先頭グループを明示的にオーバーライドする必要がある。
     * OFFのときはトラック種別ごと無効化して、デコード自体を止める。
     */
    private fun applyTextTrackSelection() {
        val player = exoPlayer ?: return
        val builder = player.trackSelectionParameters.buildUpon()
        if (captionEnabled && textGroups.isNotEmpty()) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(textGroups[0].mediaTrackGroup, listOf(0)))
        } else {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        player.trackSelectionParameters = builder.build()
        if (!captionEnabled) subtitleView?.setCues(null)
        Log.d(TAG, "applyTextTrackSelection: enabled=$captionEnabled groups=${textGroups.size}")
    }

    fun toggleCaption() {
        captionEnabled = !captionEnabled
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit().putBoolean(PREF_CAPTION_ENABLED, captionEnabled).apply()
        if (!captionEnabled) {
            pendingCaptions.clear()
            captionExpiryPositionMs = C.TIME_UNSET
            overlayView?.clearCaptions()
        }
        applyTextTrackSelection()
        val msg = if (captionEnabled) R.string.caption_on else R.string.caption_off
        showQuickToast(getString(msg))
    }

    fun toggleSuperimpose() {
        superimposeEnabled = !superimposeEnabled
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit().putBoolean(PREF_SUPERIMPOSE_ENABLED, superimposeEnabled).apply()
        if (!superimposeEnabled) {
            pendingSuperimposes.clear()
            superimposeExpiryPositionMs = C.TIME_UNSET
            overlayView?.clearSuperimpose()
        }
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
        captionTickerScheduled = false
        resetCaptionScheduling()
        tsProbeExecutor.shutdownNow()
        overlayView?.clearAll()
        subtitleView?.setCues(null)
        destroyAribSessions()
        exoPlayer?.release()
        exoPlayer = null
        overlayView = null
        subtitleView = null
    }

    override fun onPause() {
        super.onPause()
        savePlaybackPosition()
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
        // 収録中TS追いかけ再生(Issue #42)専用のEOF手前マージン。SEEK_POINT_INTERVAL_MS
        // (シークバー目盛り間隔という別の関心事のための定数、15秒)は流用しない——STATE_ENDED
        // のたびにこの秒数だけ巻き戻って見えると追いかけ再生の体験として不自然なため、
        // 日本の地上波/BS放送のキーフレーム(GOP)間隔の目安(2秒)より短く1秒にする。
        private const val TS_CATCHUP_SAFETY_MARGIN_MS = 1_000L
        // ライブHLSウォームアップ中のリトライ回数・間隔（20回 x 2秒 = 最大40秒程度待つ）
        private const val LIVE_WARMUP_RETRY_COUNT = 20
        private const val LIVE_WARMUP_RETRY_DELAY_MS = 2_000L
        // レジューム再生で位置を記録し始める下限。これ未満で終了した場合は「まだ見ていない」
        // とみなし、前回の記録もそのまま残す(savePlaybackPosition参照)。
        private const val RESUME_MIN_POSITION_MS = 10_000L
        // 終端とみなすマージン。ここまで見ていたら記録を消して次回は最初から再生する。
        private const val RESUME_END_MARGIN_MS = 15_000L
        private const val PREF_CAPTION_ENABLED = "pref_caption_enabled"
        private const val PREF_SUPERIMPOSE_ENABLED = "pref_superimpose_enabled"
        private const val PREF_SUB_AUDIO = "pref_sub_audio"
        private const val QUICK_TOAST_DURATION_MS = 1000L
        // ARIB字幕の半透明パレット(kB24ColorCLUTのアルファ128の組)に合わせた黒の下地。
        private val SUBTITLE_BACKGROUND_COLOR = Color.argb(128, 0, 0, 0)
        // テキストのCueの下端を画面上端から何割の位置に置くか(=下端から20%空ける)。
        // tx3gパーサの既定0.85はTVだと下に寄りすぎる。adjustCue()参照。
        private const val SUBTITLE_LINE_FRACTION = 0.80f
        // 字幕の文字サイズ(画面高に対する比)。SubtitleViewの既定と同値。
        private const val SUBTITLE_TEXT_SIZE_FRACTION = 0.0533f
        // 字幕の表示/消去判定を行う間隔。Leanbackのシークバー更新(UPDATE_PERIOD_MS)と
        // 同程度の粒度があれば字幕の出し入れとしては十分。
        private const val CAPTION_TICK_MS = 100L
        // ARIB字幕の表示継続時間として採用する範囲。デコーダが返す値が極端な場合の歯止め。
        private const val CAPTION_MIN_DURATION_MS = 500L
        private const val CAPTION_MAX_DURATION_MS = 10_000L
        private const val SUPERIMPOSE_MAX_DURATION_MS = 30_000L

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
        // 文字スーパー(SI)はARIB固有機能のため、tsreadexネイティブ処理を使っている
        // ときだけ扱える。CC/音声と違い再生中に増減しないので固定値で持つ。
        private val hasSuperimpose: Boolean,
        private val isLive: Boolean,
        captionEnabled: Boolean,
        superimposeEnabled: Boolean,
        preferSubAudio: Boolean,
        private var hasSubtitle: Boolean,
        private var hasSubAudio: Boolean,
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

        // CC/SI/音声ボタンの挿入位置。これらはトラック検出のタイミングで出入りするため、
        // superが追加した再生系ボタンの直後を予約しておき、常に同じ位置・同じ順序で
        // 入れ直す(検出のたびに並びが変わると操作を覚えられないため)。
        private var trackActionIndex = 0

        override fun onCreatePrimaryActions(primaryActionsAdapter: ArrayObjectAdapter) {
            super.onCreatePrimaryActions(primaryActionsAdapter)
            primaryActions = primaryActionsAdapter
            trackActionIndex = primaryActionsAdapter.size()
            if (isLive) {
                primaryActionsAdapter.add(recordAction)
                primaryActionsAdapter.add(infoAction)
            }
            refreshTrackActions()
        }

        /** 検出したトラック構成に合わせてCC/音声ボタンを出し入れする。 */
        fun updateTrackActions(hasSubtitle: Boolean, hasSubAudio: Boolean) {
            this.hasSubtitle = hasSubtitle
            this.hasSubAudio = hasSubAudio
            refreshTrackActions()
        }

        private fun refreshTrackActions() {
            val adapter = primaryActions ?: return
            // 部分的に足し引きすると順序が崩れるため、いったん全て外してから入れ直す。
            for (action in listOf(ccAction, superimposeAction, audioAction)) {
                val idx = adapter.indexOf(action)
                if (idx >= 0) adapter.removeItems(idx, 1)
            }
            var idx = trackActionIndex
            if (hasSubtitle) adapter.add(idx++, ccAction)
            if (hasSuperimpose) adapter.add(idx++, superimposeAction)
            if (hasSubAudio) adapter.add(idx, audioAction)
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
