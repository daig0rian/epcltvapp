package com.daigorian.epcltvapp

import android.graphics.Paint
import androidx.core.view.updatePadding
import androidx.leanback.R as LeanbackR
import androidx.leanback.media.PlaybackBaseControlGlue
import androidx.leanback.widget.AbstractDetailsDescriptionPresenter

/**
 * 再生画面の操作パネルに出すタイトルとサブタイトル。
 *
 * Leanback 既定のもの（[androidx.leanback.media.PlaybackTransportControlGlue] の
 * onCreateRowPresenter が作る）と同じ内容に、**タイトルの上端が切れないための余白**を足したもの。
 *
 * ## 切れる理由
 *
 * [AbstractDetailsDescriptionPresenter] は、タイトルのベースラインを説明欄の上端から
 * `lb_details_description_title_baseline`（26dp）下に置くため、タイトルの上マージンを
 * 「26dp + ascent」とする。34sp の Roboto の ascent は約 31.5dp なのでこの値は負になり、
 * タイトルの TextView は説明欄の上端からはみ出す（density 2.0 で 11px）。
 *
 * 再生画面のレイアウト（lb_playback_transport_controls_row.xml）では、説明欄の親
 * （description_dock・controls_card）が子を自分の範囲で切り抜くので、はみ出た分は描かれない。
 * 英字は 26dp の内側に収まるので問題にならないが、漢字・かなは字の頭が欠ける。
 *
 * Android 9 以降で、日本語の代替フォントの ascent が Roboto より大きい機種（AOSP 標準の
 * NotoSansCJK.ttc を使う Google TV Streamer など）では、行が広がってベースラインが下がるので
 * たまたま切れない。メーカーが別の日本語フォントを入れている機種（BRAVIA）や、Android 8.1 以前の
 * 機種（Fire TV Stick 第2世代）では切れる。Leanback 1.3.0-alpha02 時点でも直っていない。
 *
 * ## 直し方
 *
 * はみ出す分だけ説明欄の上にパディングを入れ、TextView 全体を説明欄の内側に収める。
 * TextView の中で字がどこに描かれるかは変えないので、代替フォントの寸法や Android の
 * バージョンによらず効く。説明欄が上へ広がるだけで、その分は何も描かれない。
 *
 * 詳細画面（[DetailsDescriptionPresenter]）も同じ計算でタイトルをはみ出させているが、
 * あちらの親は clipChildren="false" で切り抜かないので、手を入れていない。
 */
class PlaybackDescriptionPresenter : AbstractDetailsDescriptionPresenter() {

    override fun onBindDescription(vh: AbstractDetailsDescriptionPresenter.ViewHolder, item: Any) {
        val glue = item as PlaybackBaseControlGlue<*>
        vh.title.text = glue.title
        vh.subtitle.text = glue.subtitle
        vh.view.updatePadding(top = titleOverflow(vh))
    }

    /**
     * Leanback が付けるタイトルの上マージン（負の値）を正にしたもの。はみ出さなければ0。
     *
     * 計算は [AbstractDetailsDescriptionPresenter.ViewHolder] のコンストラクタと同じ。
     * 文字の大きさは TextView の今の値ではなく既定の 34sp を使う。タイトルが2行になると
     * ResizingTextView が文字を小さくするが、Leanback のマージンは 34sp で計算したまま変わらないため。
     */
    private fun titleOverflow(vh: AbstractDetailsDescriptionPresenter.ViewHolder): Int {
        val res = vh.view.resources
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // TextView と同じく整数ピクセルに丸めた値を使う
            textSize = res.getDimensionPixelSize(
                LeanbackR.dimen.lb_details_description_title_text_size
            ).toFloat()
            typeface = vh.title.typeface
        }
        val baseline = res.getDimensionPixelSize(LeanbackR.dimen.lb_details_description_title_baseline)
        // ascent は負
        return maxOf(0, -(baseline + paint.fontMetricsInt.ascent))
    }
}
