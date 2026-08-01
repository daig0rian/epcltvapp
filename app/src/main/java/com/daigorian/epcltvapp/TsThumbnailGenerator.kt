package com.daigorian.epcltvapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.leanback.widget.PlaybackSeekDataProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import okhttp3.OkHttpClient

private const val TAG = "TsThumbnailGenerator"

/** シーク点10点に1点程度の割合でのみサムネイルを生成する(全点は生成コストに見合わないため)。 */
private const val THUMBNAIL_DENSITY = 10

/**
 * TSシーク点の一部にサムネイルを付与する。[androidx.media3.inspector.frame.FrameExtractor]
 * (ExoPlayer自身のTsExtractorを使ってフレームをデコードするAPI)を使う。
 *
 * 実機検証により、FrameExtractorが内部で使うExoPlayerの`seekTo()`(既に開いたストリーム内での
 * シーク)はこのアプリのARIB TSに対して機能しないことが判明した(要求位置に関わらず常に
 * 準備直後の最初のフレームを返す——mediaのソースコード上のコメント
 * 「If the seek resolves to the current position, ... No frames are rendered. Repeat the
 * previously returned frame.」と一致する挙動)。
 *
 * そのため`seekTo()`には一切頼らず、Phase 1のシーク機構(TsSeekDataProvider.estimateByteOffset
 * + TsReadexDataSource.startByteOffset)を流用する。シーク点ごとに、その概算バイト位置から
 * 開始するMediaSourceを都度新規に構築し(FrameExtractor 1.10.0+のBuilder.setMediaSourceFactory
 * で注入)、「そのストリームの先頭付近のフレーム」を取得する([FrameExtractor.getThumbnail]、
 * 常にpositionMs=0相当を要求するため、機能しないseekTo()経路を通らない)。
 *
 * [PlaybackSeekDataProvider.getThumbnail]は生成が終わっていない点についてはコールバックを
 * 呼ばないことで「サムネイル無し」を表現する(全シーク点にサムネイルを敷き詰めるわけでは
 * ないため)。
 */
@UnstableApi
internal class TsThumbnailGenerator(
    private val context: Context,
    private val videoUrl: String,
    private val httpClient: OkHttpClient,
    private val positionsMs: LongArray,
    private val estimateByteOffset: (relativeTimeMs: Long) -> Long,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
    private val cache = HashMap<Int, Bitmap>()
    private val pendingCallbacks = HashMap<Int, PlaybackSeekDataProvider.ResultCallback>()
    private val openExtractors = ArrayList<FrameExtractor>()
    private var started = false
    private var released = false

    /** 二分木のBFS順(全体の中間点→前半/後半の中間点→…)で対象点を1回だけ順次生成する。 */
    fun start() {
        if (started || positionsMs.isEmpty()) return
        started = true
        val order = buildBfsOrder(positionsMs.size, THUMBNAIL_DENSITY)
        Log.d(TAG, "start: generating ${order.size}/${positionsMs.size} thumbnails")
        generateNext(order, 0)
    }

    /** [PlaybackSeekDataProvider.getThumbnail]からの委譲先。 */
    fun getThumbnail(index: Int, callback: PlaybackSeekDataProvider.ResultCallback) {
        val cached = cache[index]
        if (cached != null) {
            callback.onThumbnailLoaded(cached, index)
            return
        }
        pendingCallbacks[index] = callback
    }

    /** [PlaybackSeekDataProvider.reset]からの委譲先。生成中のバックグラウンド処理自体は継続する。 */
    fun reset() {
        pendingCallbacks.clear()
    }

    /** フラグメント破棄時に呼び、FrameExtractorのネイティブリソースを解放する。 */
    fun release() {
        released = true
        openExtractors.forEach { it.close() }
        openExtractors.clear()
    }

    private fun generateNext(order: List<Int>, cursor: Int) {
        if (released || cursor >= order.size) return
        val index = order[cursor]
        val byteOffset = estimateByteOffset(positionsMs[index])

        // ネイティブtsreadex処理(ARIB字幕等)はサムネイル用途では不要なため無効化し、
        // 単純なバイト位置オフセットの素通しだけを行う。
        val tsFactory = TsReadexDataSource.Factory(dataSourceFactory).apply {
            startByteOffset = byteOffset
            nativeProcessingEnabled = false
        }
        val mediaSourceFactory = ProgressiveMediaSource.Factory(tsFactory)
        // mediaIdを点ごとに変えることで、FrameExtractor内部の「同一MediaItemなら再利用」
        // 判定(needsPrepare)を確実に不成立にし、都度新しいMediaSource(=新しいバイト位置)で
        // 再準備させる。
        val mediaItem = MediaItem.Builder().setMediaId("thumb-$index").setUri(videoUrl).build()
        val extractor = FrameExtractor.Builder(context, mediaItem)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        openExtractors.add(extractor)

        val future = extractor.getThumbnail()
        Futures.addCallback(
            future,
            object : FutureCallback<FrameExtractor.Frame> {
                override fun onSuccess(result: FrameExtractor.Frame?) {
                    if (result != null) {
                        Log.d(
                            TAG,
                            "[調査] index=$index requestedMs=${positionsMs[index]} byteOffset=$byteOffset " +
                                "presentationTimeMs=${result.presentationTimeMs} " +
                                "size=${result.bitmap.width}x${result.bitmap.height}",
                        )
                        cache[index] = result.bitmap
                        pendingCallbacks.remove(index)?.onThumbnailLoaded(result.bitmap, index)
                    }
                    generateNext(order, cursor + 1)
                }

                override fun onFailure(t: Throwable) {
                    Log.w(TAG, "getThumbnail failed for index=$index byteOffset=$byteOffset", t)
                    generateNext(order, cursor + 1)
                }
            },
            mainExecutor,
        )
    }

    private fun buildBfsOrder(size: Int, density: Int): List<Int> {
        val targetCount = (size / density).coerceAtLeast(1)
        val result = ArrayList<Int>(targetCount)
        val queue = ArrayDeque<IntRange>()
        queue.addLast(0 until size)
        while (result.size < targetCount && queue.isNotEmpty()) {
            val range = queue.removeFirst()
            if (range.isEmpty()) continue
            val mid = (range.first + range.last) / 2
            result.add(mid)
            if (range.first <= mid - 1) queue.addLast(range.first until mid)
            if (mid + 1 <= range.last) queue.addLast((mid + 1)..range.last)
        }
        return result
    }
}
