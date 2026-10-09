package com.daigorian.epcltvapp

import java.io.Serializable

/**
 * プロテクト(自動削除の対象から外す設定)の操作1つ。詳細画面のプロテクトダイアログに並ぶ
 * 選択肢1行に当たる。
 *
 * Android に依存しないので、単体テストから直接呼べる。
 */
data class ProtectOperation(
    /** シリーズ全体が対象なら true。false なら今開いている1本だけ。 */
    val isSeries: Boolean,
    /** プロテクトをかけるなら true、外すなら false。 */
    val protect: Boolean,
    /** ダイアログに出す対象の名前。単体なら番組名、シリーズならシリーズ名。 */
    val targetName: String,
    /**
     * ダイアログに出す本数。シリーズでは、すでに目的の状態になっているものも含めた全体の数——
     * 利用者が思い浮かべる「このシリーズ」の大きさと合わせるため。
     */
    val totalCount: Int,
    /** 実際に要求を送る録画のID。すでに目的の状態になっているものは含めない。 */
    val idsToChange: List<Long>,
) : Serializable {

    companion object {
        internal const val serialVersionUID = 0L

        /**
         * ダイアログに出す選択肢を、上から並べる順に返す。
         *
         * 単体の操作は必ず1つ出す。今の状態を反転させる向きだけを出すので、かける・外すの
         * どちらか一方になる。
         *
         * シリーズの操作は、その操作で状態が変わる録画が1本でもあるときだけ出す。プロテクト済みと
         * 未プロテクトが混ざっているシリーズでは、かける・外すの両方が並ぶ。
         *
         * @param series シリーズの全件。取得できなかった・シリーズ名が無い場合は null。
         *               1本しか無いシリーズは単体の操作と同じ意味になるので、シリーズの操作は出さない。
         */
        fun choicesFor(
            currentId: Long,
            currentName: String,
            currentProtected: Boolean,
            series: SeriesPlaylist?,
        ): List<ProtectOperation> {
            val choices = mutableListOf(
                ProtectOperation(
                    isSeries = false,
                    protect = !currentProtected,
                    targetName = currentName,
                    totalCount = 1,
                    idsToChange = listOf(currentId),
                )
            )
            val entries = series?.entries.orEmpty()
            if (series == null || entries.size < 2) return choices

            // かける側を先に並べる。このボタンを押す目的として多いほうを上に置く。
            for (protect in listOf(true, false)) {
                val ids = entries.filter { it.isProtected != protect }.map { it.id }
                if (ids.isEmpty()) continue
                choices += ProtectOperation(
                    isSeries = true,
                    protect = protect,
                    targetName = series.seriesTitle,
                    totalCount = entries.size,
                    idsToChange = ids,
                )
            }
            return choices
        }
    }
}
