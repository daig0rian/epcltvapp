package com.daigorian.epcltvapp

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
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

/**
 * サムネイル生成1回あたり約1.3秒かかる(パイプライン新規構築コストが支配的で、
 * デコーダ種別を変えても改善しなかった実機検証結果より)。全シーク点ごとに生成すると
 * 総待ち時間が非現実的になるため、隣接するBUCKET_SIZE点をまとめて1回の生成で賄う。
 */
private const val BUCKET_SIZE = 10

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
 * 生成は[getThumbnail]が実際に呼ばれた時点でのオンデマンドとする。Leanbackは表示中の
 * シークステップ全部に対してgetThumbnail()を呼び、コールバックが来ていない間は直前に
 * 届いたBitmapを表示し続ける(=間のステップは自動的に埋まる)ため、こちらで事前生成
 * スケジュールを組む必要はない。[PlaybackSeekDataProvider.getThumbnail]は
 * 「UIスレッドから呼ばれる」契約なので、FrameExtractorのスレッド固定要件もこれで自然に
 * 満たされる(呼び出し元を別途mainスレッドに揃える必要がない)。
 *
 * 生成1回のコストが高いため、リクエストされた個々のindexではなく[BUCKET_SIZE]点単位の
 * バケットで生成する(オンデマンドである点は変わらない——「触れられたバケットだけ、
 * 触れられた時に」生成する)。1つのバケットの生成結果は、そのバケットに属する
 * 全ての生インデックスへのコールバックにまとめて配る。
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
    /** バケット代表index → Bitmap。 */
    private val cache = HashMap<Int, Bitmap>()
    /** 生インデックス → コールバック(同一インデックスなら常に最新のものへ差し替える)。 */
    private val pendingCallbacks = HashMap<Int, PlaybackSeekDataProvider.ResultCallback>()
    /** 生成中のバケット代表index。 */
    private val inFlightBuckets = HashSet<Int>()
    private val openExtractors = ArrayList<FrameExtractor>()
    private var released = false

    private fun bucketOf(index: Int): Int = (index / BUCKET_SIZE) * BUCKET_SIZE

    /** [PlaybackSeekDataProvider.getThumbnail]からの委譲先。所属バケットが未生成ならその場で生成を開始する。 */
    fun getThumbnail(index: Int, callback: PlaybackSeekDataProvider.ResultCallback) {
        val bucket = bucketOf(index)
        val cached = cache[bucket]
        if (cached != null) {
            callback.onThumbnailLoaded(cached, index)
            return
        }
        pendingCallbacks[index] = callback
        if (inFlightBuckets.add(bucket)) {
            generate(bucket)
        }
    }

    /** [PlaybackSeekDataProvider.reset]からの委譲先。進行中の生成自体はキャッシュに残るため継続させる。 */
    fun reset() {
        pendingCallbacks.clear()
    }

    /** フラグメント破棄時に呼び、FrameExtractorのネイティブリソースを解放する。 */
    fun release() {
        released = true
        openExtractors.forEach { it.close() }
        openExtractors.clear()
    }

    /** @param bucket [bucketOf]で丸めた代表index。 */
    private fun generate(bucket: Int) {
        if (released || bucket !in positionsMs.indices) {
            inFlightBuckets.remove(bucket)
            return
        }
        val byteOffset = estimateByteOffset(positionsMs[bucket])

        // ネイティブtsreadex処理(ARIB字幕等)はサムネイル用途では不要なため無効化し、
        // 単純なバイト位置オフセットの素通しだけを行う。
        val tsFactory = TsReadexDataSource.Factory(dataSourceFactory).apply {
            startByteOffset = byteOffset
            nativeProcessingEnabled = false
        }
        val mediaSourceFactory = ProgressiveMediaSource.Factory(tsFactory)
        // mediaIdをバケットごとに変えることで、FrameExtractor内部の「同一MediaItemなら再利用」
        // 判定(needsPrepare)を確実に不成立にし、都度新しいMediaSource(=新しいバイト位置)で
        // 再準備させる。
        val mediaItem = MediaItem.Builder().setMediaId("thumb-$bucket").setUri(videoUrl).build()
        val extractor = FrameExtractor.Builder(context, mediaItem)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        openExtractors.add(extractor)

        val startedAtMs = SystemClock.elapsedRealtime()
        val future = extractor.getThumbnail()
        Futures.addCallback(
            future,
            object : FutureCallback<FrameExtractor.Frame> {
                override fun onSuccess(result: FrameExtractor.Frame?) {
                    inFlightBuckets.remove(bucket)
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                    Log.d(TAG, "[調査] bucket=$bucket byteOffset=$byteOffset elapsedMs=$elapsedMs 成功")
                    if (result != null) {
                        cache[bucket] = result.bitmap
                        dispatchToBucket(bucket, result.bitmap)
                    }
                }

                override fun onFailure(t: Throwable) {
                    inFlightBuckets.remove(bucket)
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                    Log.w(TAG, "[調査] bucket=$bucket byteOffset=$byteOffset elapsedMs=$elapsedMs 失敗", t)
                }
            },
            mainExecutor,
        )
    }

    /** このバケットの生成結果を待っていた全ての生インデックスのコールバックへ配る。 */
    private fun dispatchToBucket(bucket: Int, bitmap: Bitmap) {
        val iterator = pendingCallbacks.entries.iterator()
        while (iterator.hasNext()) {
            val (pendingIndex, pendingCallback) = iterator.next()
            if (bucketOf(pendingIndex) == bucket) {
                pendingCallback.onThumbnailLoaded(bitmap, pendingIndex)
                iterator.remove()
            }
        }
    }
}
