package com.daigorian.epcltvapp

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * ARIB字幕/文字スーパーを動画の上に重ねて描画するだけのビュー。
 *
 * 「いつ出していつ消すか」の時間管理は持たない——表示時間は壁時計ではなく再生位置で
 * 数える必要があり(一時停止中に字幕が勝手に進んでしまうため)、再生位置を知っている
 * [PlaybackVideoFragment] 側が [showCaptions]/[clearCaptions] の呼び出しで制御する。
 */
internal class SubtitleOverlayView(ctx: Context) : View(ctx) {

    private var captionImages: Array<CaptionImage> = emptyArray()
    private var superimposeImages: Array<CaptionImage> = emptyArray()
    private var videoWidth = 1920
    private var videoHeight = 1080

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
    }

    fun setVideoSize(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            videoWidth = w
            videoHeight = h
        }
    }

    fun showCaptions(images: Array<CaptionImage>) {
        captionImages = images
        invalidate()
    }

    fun clearCaptions() {
        if (captionImages.isEmpty()) return
        captionImages = emptyArray()
        invalidate()
    }

    fun showSuperimpose(images: Array<CaptionImage>) {
        superimposeImages = images
        invalidate()
    }

    fun clearSuperimpose() {
        if (superimposeImages.isEmpty()) return
        superimposeImages = emptyArray()
        invalidate()
    }

    fun clearAll() {
        clearCaptions()
        clearSuperimpose()
    }

    override fun onDraw(canvas: Canvas) {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val scaleX = viewW / videoWidth.toFloat()
        val scaleY = viewH / videoHeight.toFloat()

        drawImages(canvas, captionImages, scaleX, scaleY)
        drawImages(canvas, superimposeImages, scaleX, scaleY)
    }

    private fun drawImages(canvas: Canvas, images: Array<CaptionImage>, scaleX: Float, scaleY: Float) {
        for (img in images) {
            if (img.bitmap.isRecycled) continue
            canvas.save()
            canvas.translate(img.x * scaleX, img.y * scaleY)
            canvas.scale(scaleX, scaleY)
            canvas.drawBitmap(img.bitmap, 0f, 0f, null)
            canvas.restore()
        }
    }
}
