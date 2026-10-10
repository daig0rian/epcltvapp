package com.daigorian.epcltvapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * ホームのタイトル文字「EPGStation」の下線を兼ねる、ディスク使用量の棒。
 *
 * 保存先1つにつき1本を縦に積む。棒は使用済みと空きの2色で、その上にラベルを重ねる。
 * ラベルは2色の境目をまたぐので、区間ごとに文字色を変えて2回描く（どちらの上でも読めるように）。
 *
 * 幅は置く側が決める（タイトル文字の幅に合わせる）。高さは本数から決まる。
 */
class DiskUsageBarView(context: Context) : View(context) {

    private val barHeight = resources.getDimensionPixelSize(R.dimen.disk_usage_bar_height)
    private val barSpacing = resources.getDimensionPixelSize(R.dimen.disk_usage_bar_spacing)
    private val textPadding = resources.getDimensionPixelSize(R.dimen.disk_usage_bar_text_padding)

    private val usedPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.disk_usage_bar_used)
    }
    private val freePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.disk_usage_bar_free)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = resources.getDimension(R.dimen.disk_usage_bar_text_size)
    }
    private val textOnUsedColor = ContextCompat.getColor(context, R.color.disk_usage_bar_text_on_used)
    private val textOnFreeColor = ContextCompat.getColor(context, R.color.disk_usage_bar_text_on_free)

    private var entries: List<DiskUsage> = emptyList()

    /** [entries] と同じ並びの、幅に合わせて名前を切り詰めたラベル。 */
    private var labels: List<String> = emptyList()

    /** 出すものが無いか。 */
    val isEmpty: Boolean get() = entries.isEmpty()

    /** いまの本数で要る高さ（px）。 */
    val contentHeight: Int
        get() = if (entries.isEmpty()) 0 else entries.size * barHeight + (entries.size - 1) * barSpacing

    init {
        // 飾りではなく情報なので、読み上げの対象にする（中身は setEntries で入れる）
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** 出す保存先を差し替える。空なら何も描かない。 */
    fun setEntries(newEntries: List<DiskUsage>) {
        if (newEntries == entries) return
        val wasEmpty = entries.isEmpty()
        entries = newEntries
        rebuildLabels()
        contentDescription = entries.joinToString { "${it.labelName(entries.size)} ${it.labelBody}" }
        // 本数が変わると高さが変わる。置く側は親のレイアウトをきっかけに位置を合わせ直す。
        requestLayout()
        invalidate()
        if (wasEmpty && entries.isNotEmpty()) {
            // タイトルより遅れて届くので、いきなり現れないようにする
            animate().cancel()
            alpha = 0f
            animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), contentHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) rebuildLabels()
    }

    override fun onDraw(canvas: Canvas) {
        val right = width.toFloat()
        // 文字を棒の上下中央に置く
        val textOffset = barHeight / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        entries.forEachIndexed { index, entry ->
            val top = (index * (barHeight + barSpacing)).toFloat()
            val bottom = top + barHeight
            // 境目は画素に合わせる（半端な位置だと2色がにじむ）
            val split = (right * entry.usedRatio).roundToInt().toFloat()
            canvas.drawRect(0f, top, split, bottom, usedPaint)
            canvas.drawRect(split, top, right, bottom, freePaint)

            val label = labels.getOrNull(index) ?: return@forEachIndexed
            val baseline = top + textOffset
            drawLabel(canvas, label, baseline, 0f, top, split, bottom, textOnUsedColor)
            drawLabel(canvas, label, baseline, split, top, right, bottom, textOnFreeColor)
        }
    }

    /** ラベルのうち、指定した区間にかかる部分だけを [color] で描く。 */
    private fun drawLabel(
        canvas: Canvas, label: String, baseline: Float,
        left: Float, top: Float, right: Float, bottom: Float, color: Int,
    ) {
        if (right <= left) return
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        textPaint.color = color
        canvas.drawText(label, textPadding.toFloat(), baseline, textPaint)
        canvas.restore()
    }

    /**
     * 幅に収まるようにラベルを作り直す。
     *
     * 切り詰めるのは名前だけ。保存先名は利用者が config.yml で自由に付けるので長さが読めないが、
     * 使用量と使用率は必ず読めるようにしておきたい。
     */
    private fun rebuildLabels() {
        if (width == 0) {
            labels = emptyList()
            return
        }
        val available = (width - 2 * textPadding).toFloat()
        labels = entries.map { entry ->
            val body = entry.labelBody
            val nameWidth = (available - textPaint.measureText(" $body")).coerceAtLeast(0f)
            val name = TextUtils.ellipsize(
                entry.labelName(entries.size), textPaint, nameWidth, TextUtils.TruncateAt.END
            )
            "$name $body".trim()
        }
    }

    companion object {
        /** 最初に現れるときのフェードの時間（ms）。 */
        private const val FADE_IN_MS = 200L
    }
}
