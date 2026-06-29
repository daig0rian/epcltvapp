package com.daigorian.epcltvapp

import android.content.Context
import android.graphics.Canvas
import android.view.View

internal class SubtitleOverlayView(ctx: Context) : View(ctx) {

    private var captionImages: Array<CaptionImage> = emptyArray()
    private var superimposeImages: Array<CaptionImage> = emptyArray()
    private var videoWidth = 1920
    private var videoHeight = 1080

    private val clearCaptionRunnable = Runnable { clearCaptions() }
    private val clearSuperimposeRunnable = Runnable { clearSuperimpose() }

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
        removeCallbacks(clearCaptionRunnable)
        captionImages = images
        invalidate()
        if (images.isNotEmpty()) {
            val durationMs = images[0].durationMs.coerceIn(500, 10_000)
            postDelayed(clearCaptionRunnable, durationMs)
        }
    }

    fun clearCaptions() {
        removeCallbacks(clearCaptionRunnable)
        captionImages = emptyArray()
        invalidate()
    }

    fun showSuperimpose(images: Array<CaptionImage>) {
        removeCallbacks(clearSuperimposeRunnable)
        superimposeImages = images
        invalidate()
        if (images.isNotEmpty()) {
            val durationMs = images[0].durationMs.coerceIn(500, 30_000)
            postDelayed(clearSuperimposeRunnable, durationMs)
        }
    }

    fun clearSuperimpose() {
        removeCallbacks(clearSuperimposeRunnable)
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
