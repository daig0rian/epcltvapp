package com.daigorian.epcltvapp

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * 画面の左右の端から飛び出して光り、引っ込む丸い矢印。ライブ視聴中に左右キーで局を変えたときに出し、
 * 左右キーに役目があることを伝える（Android の戻るジェスチャーで画面端に出る矢印と同じ考え方）。
 *
 * 置く側（[PlaybackActivity]）が、矢印の向きの端に寄せて縦の中央に置く。ふだんは画面の外に隠れている。
 */
class EdgeArrowView(context: Context, private val pointsRight: Boolean) : View(context) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(ARROW_STROKE_DP)
    }
    private val arrowPath = Path()
    private val argb = ArgbEvaluator()
    private var running: Animator? = null

    /** 0 = ふだんの色、1 = 選ばれた色。 */
    private var highlight = 0f
        set(value) {
            field = value
            invalidate()
        }

    init {
        isFocusable = false
        // 画面の外に隠しておく。端からの距離ぶんも余計に出す。
        translationX = hiddenTranslationX()
    }

    /** 端から飛び出し、選ばれたように光ってから引っ込む。動いている最中に呼ばれたら、今の位置からやり直す。 */
    fun play() {
        running?.cancel()
        val slideIn = ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f).apply {
            duration = SLIDE_IN_MS
            interpolator = DecelerateInterpolator()
        }
        val select = AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofFloat(highlight, 1f).apply { addUpdateListener { highlight = it.animatedValue as Float } },
                ObjectAnimator.ofFloat(this@EdgeArrowView, SCALE_X, 1f, SELECTED_SCALE, 1f),
                ObjectAnimator.ofFloat(this@EdgeArrowView, SCALE_Y, 1f, SELECTED_SCALE, 1f)
            )
            duration = SELECT_MS
        }
        // 光ったまま少し留める。入れ子の AnimatorSet の startDelay は古い Android で効き方が揃わないので、
        // 何もしないアニメーションを挟んで待つ。
        val hold = ValueAnimator.ofFloat(0f, 1f).apply { duration = HOLD_MS }
        val slideOut = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(this@EdgeArrowView, TRANSLATION_X, hiddenTranslationX()),
                ValueAnimator.ofFloat(1f, 0f).apply { addUpdateListener { highlight = it.animatedValue as Float } }
            )
            duration = SLIDE_OUT_MS
            interpolator = AccelerateInterpolator()
        }
        running = AnimatorSet().apply {
            playSequentially(slideIn, select, hold, slideOut)
            start()
        }
    }

    override fun onDetachedFromWindow() {
        running?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(DIAMETER_DP).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = width / 2f
        circlePaint.color = argb.evaluate(highlight, IDLE_CIRCLE_COLOR, SELECTED_CIRCLE_COLOR) as Int
        arrowPaint.color = argb.evaluate(highlight, IDLE_ARROW_COLOR, SELECTED_ARROW_COLOR) as Int
        canvas.drawCircle(radius, radius, radius, circlePaint)

        // 「>」の形。向きに合わせて左右を反転する。先端を少し進行方向へずらすと、丸の中で真ん中に見える。
        val half = radius * ARROW_SIZE_RATIO
        val direction = if (pointsRight) 1f else -1f
        val tipX = radius + direction * half / 2
        arrowPath.reset()
        arrowPath.moveTo(tipX - direction * half, radius - half)
        arrowPath.lineTo(tipX, radius)
        arrowPath.lineTo(tipX - direction * half, radius + half)
        canvas.drawPath(arrowPath, arrowPaint)
    }

    /** 画面の外へ隠れる位置。端からの距離と丸の大きさのぶんだけ、向きの側へずらす。 */
    private fun hiddenTranslationX(): Float {
        val distance = dp(DIAMETER_DP) + dp(EDGE_MARGIN_DP)
        return if (pointsRight) distance else -distance
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        /** 丸の直径。 */
        private const val DIAMETER_DP = 64f
        /**
         * 画面の端から丸までの距離。テレビは画面の端が切れて映ることがあるので、Leanback の画面の余白
         * （オーバースキャン）と同じだけ空ける。
         */
        const val EDGE_MARGIN_DP = 48f
        private const val ARROW_STROKE_DP = 4f
        /** 矢印の半分の高さ（丸の半径に対する割合）。 */
        private const val ARROW_SIZE_RATIO = 0.36f
        private const val SELECTED_SCALE = 1.15f
        private const val SLIDE_IN_MS = 160L
        private const val SELECT_MS = 180L
        private const val HOLD_MS = 250L
        private const val SLIDE_OUT_MS = 200L
        private val IDLE_CIRCLE_COLOR = Color.argb(0x99, 0, 0, 0)
        private val IDLE_ARROW_COLOR = Color.WHITE
        private val SELECTED_CIRCLE_COLOR = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        private val SELECTED_ARROW_COLOR = Color.parseColor("#363636")
    }
}
