package com.daigorian.epcltvapp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.SubtitleView

/**
 * 「エンコード済み動画で字幕の更新だけが数秒〜数十秒止まる」現象を捕まえるための常設計測。
 *
 * **これは調査用の一時的なコードで、原因が判明したら丸ごと削除する。**
 * 発生頻度が30分に1回あるかないかと低く、ブランチを切り替えるたびに計測が外れて取り逃す
 * ため、原因が分かるまで master に常駐させている。詳細は CLAUDE.md の該当節を参照。
 *
 * ## なぜ3系統を同時に見るのか
 *
 * 内蔵プレーヤーの字幕(tx3g)は「次のサンプルが来るまで現在の表示を維持する」形式で、消去も
 * “空サンプル”1件で行われる。そのため字幕の更新が止まると、表示中なら「字幕が出っぱなし」、
 * 空サンプル表示中なら「しばらく字幕が出ない」という、見え方の違う2つの症状が同じ原因から出る。
 * どこで止まったのかは、Cueが届く経路を分解して同時に記録しないと切り分けられない。
 *
 *  - `BEAT`  … メインスレッドが動いているか。250ms周期で叩き、実測間隔の最大値を出す。
 *  - `CUE`   … Cueの到着。`lag` はそのCue自身の時刻から何ms遅れて届いたか。
 *  - `DRAW`  … `setCues` した内容が実際に画面へ描かれるまでの時間。
 *  - `SEEK`  … 位置の不連続。シーク直後は復元動作でCueが大きく遅れるのが正常なため。
 *
 * ## 読み方
 *
 * | 観測 | 結論 |
 * |---|---|
 * | 詰まりの区間で `maxDt` が大きい | メインスレッド停止(配送・描画の共通経路) |
 * | `maxDt` は正常なのに `CUE` が来ない/遅れる | TextRenderer・サンプル供給側 |
 * | `CUE` は定刻なのに `DRAW` が遅い | 描画側 |
 * | 3つとも正常 | プレーヤーは正常。ファイルの字幕指定を疑う |
 *
 * 正常時の実測値(Google TV Streamer, 約4時間・Cue約2000件)は
 * `maxDt` 250〜404ms / `lag` 中央5ms・最大32ms / `DRAW` 中央9ms・最大18ms。
 * 詰まればこの3桁上になるので、しきい値との間には十分な余裕がある。
 *
 * ## ログ量
 *
 * 正常時は5秒ごとの要約1行＋Cue1件につき1行で、およそ0.5行/秒。
 * 異常時のみ `STALL_MAIN_THREAD` / `LATE_CUE` / 遅い `DRAW` を即時に出す。
 */
@UnstableApi
class SubtitleStallDiagnostics(private val player: ExoPlayer) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastBeatUptimeMs = 0L
    private var lastCueUptimeMs = 0L
    private var cueCount = 0L

    // 要約1行にまとめるための集計。正常時のログ量を抑えつつ検出の分解能は250msのまま保つ。
    private var lastSummaryUptimeMs = 0L
    private var windowMaxDtMs = 0L
    private var windowMaxDrawMs = 0L

    // 描画計測。SubtitleView は final で継承できないため、ビューツリーの描画フックで測る。
    private var subtitleView: SubtitleView? = null
    private var pendingDrawUptimeMs = 0L
    private var pendingDrawSeq = 0L
    private var pendingDrawCueCount = 0
    private val onDrawListener = ViewTreeObserver.OnDrawListener {
        if (pendingDrawUptimeMs != 0L) {
            val delay = SystemClock.uptimeMillis() - pendingDrawUptimeMs
            pendingDrawUptimeMs = 0L
            if (delay > windowMaxDrawMs) windowMaxDrawMs = delay
            if (delay >= SLOW_DRAW_MS) {
                Log.w(TAG, "SLOW_DRAW seq=$pendingDrawSeq n=$pendingDrawCueCount delay=${delay}ms")
            }
        }
    }

    // シーク直後は「その位置で有効な字幕サンプル」の復元でCueが大きく遅れて届くのが正常。
    // それを本物の詰まりと取り違えないよう、シークの時刻を覚えて猶予期間を設ける。
    private var lastSeekUptimeMs = -POST_SEEK_GRACE_MS
    private val playerListener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                lastSeekUptimeMs = SystemClock.uptimeMillis()
                Log.i(TAG, "SEEK ${oldPosition.positionMs} -> ${newPosition.positionMs}")
            }
        }
    }

    private val beat = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = now - lastBeatUptimeMs
            lastBeatUptimeMs = now
            if (dt > windowMaxDtMs) windowMaxDtMs = dt
            // 250ms周期のつもりが大きくずれた=メインスレッドが止まっていた。即時に出す。
            if (dt >= STALL_THRESHOLD_MS) {
                Log.w(TAG, "STALL_MAIN_THREAD dt=$dt (メインスレッドが${dt}ms止まった)")
            }
            if (now - lastSummaryUptimeMs >= SUMMARY_INTERVAL_MS) {
                lastSummaryUptimeMs = now
                val cueGap = if (lastCueUptimeMs == 0L) -1 else now - lastCueUptimeMs
                Log.i(
                    TAG,
                    "BEAT maxDt=$windowMaxDtMs maxDraw=$windowMaxDrawMs cueGap=$cueGap " +
                            "pos=${player.currentPosition} buf=${player.bufferedPosition} " +
                            "totalBuf=${player.totalBufferedDuration} state=${player.playbackState} " +
                            "playing=${player.isPlaying} " +
                            "drop=${player.videoDecoderCounters?.droppedBufferCount ?: -1}"
                )
                windowMaxDtMs = 0
                windowMaxDrawMs = 0
            }
            handler.postDelayed(this, BEAT_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        val now = SystemClock.uptimeMillis()
        lastBeatUptimeMs = now
        lastSummaryUptimeMs = now
        Log.i(TAG, "start: 字幕詰まり計測を開始 (BEAT間隔=${BEAT_INTERVAL_MS}ms)")
        player.addListener(playerListener)
        handler.postDelayed(beat, BEAT_INTERVAL_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(beat)
        player.removeListener(playerListener)
        subtitleView?.viewTreeObserver?.let { if (it.isAlive) it.removeOnDrawListener(onDrawListener) }
        subtitleView = null
        Log.i(TAG, "stop: 字幕詰まり計測を終了 (CUE総数=$cueCount)")
    }

    /** 描画計測の対象ビューを登録する。ビュー生成直後に1度だけ呼ぶ。 */
    fun attachSubtitleView(view: SubtitleView) {
        subtitleView = view
        view.viewTreeObserver.addOnDrawListener(onDrawListener)
    }

    /**
     * `Player.Listener.onCues` から呼ぶ。メインスレッド上で呼ばれる前提。
     * [SubtitleView.setCues] の直後に呼ぶこと(描画までの時間をここから測るため)。
     */
    fun onCues(cueGroup: CueGroup) {
        val now = SystemClock.uptimeMillis()
        val gap = if (lastCueUptimeMs == 0L) -1 else now - lastCueUptimeMs
        lastCueUptimeMs = now
        cueCount++
        pendingDrawSeq = cueCount
        pendingDrawUptimeMs = now
        pendingDrawCueCount = cueGroup.cues.size

        val pos = player.currentPosition
        val ptsMs = if (cueGroup.presentationTimeUs == C.TIME_UNSET) {
            Long.MIN_VALUE
        } else {
            cueGroup.presentationTimeUs / 1000
        }
        // このCueが本来出るべき時刻からの遅れ。tx3gは自分の時刻を presentationTime に持つので、
        // 再生位置との差がそのまま「遅れ」になる。
        val lag = if (ptsMs == Long.MIN_VALUE) -1 else pos - ptsMs
        val postSeek = now - lastSeekUptimeMs < POST_SEEK_GRACE_MS
        val text = cueGroup.cues.firstOrNull()?.text?.toString()?.replace("\n", "⏎") ?: ""
        Log.i(
            TAG,
            "CUE seq=$cueCount gap=$gap lag=$lag pos=$pos pts=$ptsMs " +
                    "buf=${player.bufferedPosition} n=${cueGroup.cues.size} " +
                    "postSeek=${if (postSeek) 1 else 0} txt=\"${text.take(24)}\""
        )
        // シーク直後の遅れは正常な復元動作なので鳴らさない(実測ではこれが誤検知の全件だった)。
        if (lag >= LATE_CUE_THRESHOLD_MS && !postSeek) {
            Log.w(TAG, "LATE_CUE lag=${lag}ms pos=$pos pts=$ptsMs txt=\"${text.take(24)}\"")
        }
    }

    companion object {
        // logcat -s SubStallDiag で拾える固定タグ。
        const val TAG = "SubStallDiag"
        private const val BEAT_INTERVAL_MS = 250L
        // 正常時はこの間隔で1行に要約する(検出の分解能は BEAT_INTERVAL_MS のまま)。
        private const val SUMMARY_INTERVAL_MS = 5_000L
        // これ以上ビートが飛んだらメインスレッドが止まっていたとみなす。
        private const val STALL_THRESHOLD_MS = 1_000L
        // Cue自身の時刻からこれ以上遅れて届いたら「詰まった」とみなす。
        private const val LATE_CUE_THRESHOLD_MS = 1_500L
        // setCues から描画までこれ以上かかったら描画側を疑う。
        private const val SLOW_DRAW_MS = 100L
        // シーク後この時間内に届いたCueは、遅れていても復元動作とみなして警告しない。
        // 実測ではシーク完了(バッファ再充填)まで最大2秒だったので、その倍を取る。
        private const val POST_SEEK_GRACE_MS = 5_000L
    }
}
