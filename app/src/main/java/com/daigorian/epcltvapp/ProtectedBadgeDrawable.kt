package com.daigorian.epcltvapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * プロテクト済みの録画のサムネイルに重ねる目印。渡された範囲の右上に、半透明の南京錠を描く。
 *
 * 大きさは範囲の高さに対する比 [sizeRatio] で決める。カードの一覧と詳細画面とでサムネイルの
 * 大きさが違い、詳細画面のものは表示時にさらに縮められるため、画素数では決められない。
 *
 * 固有の大きさは持たない(既定の -1 のまま)。LayerDrawable でサムネイルに重ねたときに、
 * 全体の大きさがサムネイルのものから変わらないようにするため。
 */
class ProtectedBadgeDrawable(context: Context, private val sizeRatio: Float) : Drawable() {
    // 透明度はリソースごとに共有されるので、mutate で切り離してから変える。
    private val icon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_lock)
        ?.mutate()
        ?.apply { alpha = ICON_ALPHA }

    // 白っぽいサムネイルの上でも形が読めるよう、南京錠の下に敷く暗い円。
    private val backingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(BACKING_ALPHA, 0, 0, 0)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val radius = bounds.height() * sizeRatio / 2f
        val margin = radius * MARGIN_RATIO
        val centerX = bounds.right - margin - radius
        val centerY = bounds.top + margin + radius
        canvas.drawCircle(centerX, centerY, radius, backingPaint)

        val iconHalf = radius * ICON_RATIO
        icon?.setBounds(
            (centerX - iconHalf).roundToInt(),
            (centerY - iconHalf).roundToInt(),
            (centerX + iconHalf).roundToInt(),
            (centerY + iconHalf).roundToInt(),
        )
        icon?.draw(canvas)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** 下敷きの円の濃さ(255で不透明)。 */
        private const val BACKING_ALPHA = 115
        /** 南京錠の濃さ(255で不透明)。 */
        private const val ICON_ALPHA = 217
        /** サムネイルの端から円までの距離。円の半径に対する比。 */
        private const val MARGIN_RATIO = 0.4f
        /** 円の中で南京錠が占める大きさ。円の直径に対する比。 */
        private const val ICON_RATIO = 0.62f
    }
}
