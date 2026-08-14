package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SeriesTitleExtractor] の回帰テスト。
 *
 * 期待値は `src/test/resources/series_golden.tsv` に置いてある。ケースの追加や
 * 期待値の修正は**そのファイルだけ**を編集すればよく、このクラスに手を入れる必要はない。
 *
 * 抽出の辞書（タグ・話数マーカー・境界記号）を変更するとここが落ちる。それは意図した
 * 動作で、落ちた内容を見て「改善なら期待値を更新」「退行なら辞書を直す」と判断するための
 * ものである。TSV のファイル冒頭にも同じ趣旨を書いてある。
 */
class SeriesTitleExtractorTest {

    private data class Case(val line: Int, val input: String, val expected: String, val note: String)

    private fun loadGoldenSet(): List<Case> {
        val stream = javaClass.getResourceAsStream(GOLDEN_SET)
            ?: throw IllegalStateException("テストデータが見つからない: $GOLDEN_SET")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.withIndex()
                .filterNot { (_, line) -> line.isBlank() || line.startsWith("#") }
                .map { (index, line) ->
                    val columns = line.split("\t")
                    check(columns.size >= 2) { "${index + 1} 行目の列が足りない: $line" }
                    Case(index + 1, columns[0], columns[1], columns.getOrElse(2) { "" })
                }
                .toList()
        }
    }

    @Test
    fun `golden set の全件でシリーズ名が期待どおり取り出せる`() {
        val cases = loadGoldenSet()
        assertTrue("テストデータが読めていない", cases.size >= 90)

        // 1件目で止めず、ずれた分をまとめて報告する。辞書を変えたときに
        // 影響範囲を一度に把握したいため。
        val failures = cases.mapNotNull { case ->
            val actual = SeriesTitleExtractor.extract(case.input)
            if (actual == case.expected) {
                null
            } else {
                """
                |  ${case.line} 行目 [${case.note}]
                |    入力  : ${case.input}
                |    期待値: 「${case.expected}」
                |    実際  : 「$actual」
                """.trimMargin()
            }
        }

        assertEquals(
            "${failures.size} 件が期待値と違う (全 ${cases.size} 件)\n" + failures.joinToString("\n"),
            0,
            failures.size
        )
    }

    @Test
    fun `空の番組名を渡しても落ちない`() {
        assertEquals("", SeriesTitleExtractor.extract(""))
    }

    @Test
    fun `シリーズ名は前後の空白を含まない`() {
        val cases = loadGoldenSet()
        val withSpace = cases
            .map { it to SeriesTitleExtractor.extract(it.input) }
            .filter { (_, actual) -> actual != actual.trim() }
            .map { (case, actual) -> "${case.line} 行目: 「$actual」" }

        assertEquals(
            "前後に空白が残っている:\n" + withSpace.joinToString("\n"),
            0,
            withSpace.size
        )
    }

    private companion object {
        const val GOLDEN_SET = "/series_golden.tsv"
    }
}
