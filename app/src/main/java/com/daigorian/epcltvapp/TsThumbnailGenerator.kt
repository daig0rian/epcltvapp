package com.daigorian.epcltvapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.leanback.widget.PlaybackSeekDataProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.FrameExtractor
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures

private const val TAG = "TsThumbnailGenerator"

/** シーク点10点に1点程度の割合でのみサムネイルを生成する(全点は生成コストに見合わないため)。 */
private const val THUMBNAIL_DENSITY = 10

/**
 * TSシーク点の一部にサムネイルを付与する。[androidx.media3.inspector.FrameExtractor]
 * (ExoPlayer自身のTsExtractorを使ってフレームをデコードするAPI)を使う。
 *
 * FrameExtractorはカスタムDataSourceを注入できず内部でMediaItemのURIを直接HTTPで開くため、
 * tsreadexは経由しない。ExoPlayer組み込みのTsExtractorはファイル先頭・末尾のPCRを
 * 軽量に読んで概算durationを求め、そこからPCRベースの二分探索でシークする機構
 * (TsDurationReader/TsBinarySearchSeeker)を元々持っており、TsReadexDataSourceが
 * 意図的に隠しているコンテンツ長がここでは素通しされるため、この機構がそのまま働く。
 * そのためTsSeekDataProviderが持つ相対時刻(ms)をそのまま渡せばよく、独自のバイト位置
 * 計算は不要。
 *
 * [PlaybackSeekDataProvider.getThumbnail]は生成が終わっていない点についてはコールバックを
 * 呼ばないことで「サムネイル無し」を表現する(全シーク点にサムネイルを敷き詰めるわけでは
 * ないため)。
 */
@UnstableApi
internal class TsThumbnailGenerator(
    private val context: Context,
    private val videoUrl: String,
    private val positionsMs: LongArray,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val cache = HashMap<Int, Bitmap>()
    private val pendingCallbacks = HashMap<Int, PlaybackSeekDataProvider.ResultCallback>()
    private var frameExtractor: FrameExtractor? = null
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
        frameExtractor?.close()
        frameExtractor = null
    }

    private fun generateNext(order: List<Int>, cursor: Int) {
        if (released || cursor >= order.size) return
        val index = order[cursor]
        val extractor = frameExtractor ?: FrameExtractor.Builder(context, MediaItem.fromUri(videoUrl))
            .build()
            .also { frameExtractor = it }
        val future = extractor.getFrame(positionsMs[index])
        Futures.addCallback(
            future,
            object : FutureCallback<FrameExtractor.Frame> {
                override fun onSuccess(result: FrameExtractor.Frame?) {
                    if (result != null) {
                        cache[index] = result.bitmap
                        pendingCallbacks.remove(index)?.onThumbnailLoaded(result.bitmap, index)
                    }
                    generateNext(order, cursor + 1)
                }

                override fun onFailure(t: Throwable) {
                    Log.w(TAG, "getFrame failed for index=$index positionMs=${positionsMs[index]}", t)
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
