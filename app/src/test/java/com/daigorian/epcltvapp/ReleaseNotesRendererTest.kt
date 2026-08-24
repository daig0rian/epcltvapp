package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReleaseNotesRenderer] のテスト。
 *
 * 見ているのは主に **Markdown の記号が画面に生のまま残らないこと** と、
 * Android の [android.text.Html] が解釈できないタグに頼っていないこと。
 * 入力は実際のリリースノートを引き写さず、記法を1つずつ含む創作にしてある。
 */
class ReleaseNotesRendererTest {

    private fun render(markdown: String) = ReleaseNotesRenderer.toHtml(markdown)

    // --- 入力が無いとき ---

    @Test
    fun nullや空は空文字になる() {
        assertEquals("", ReleaseNotesRenderer.toHtml(null))
        assertEquals("", ReleaseNotesRenderer.toHtml(""))
        assertEquals("", ReleaseNotesRenderer.toHtml("   \n  \n"))
    }

    // --- 見出し・段落・強調 ---

    @Test
    fun 見出しは見出しタグになる() {
        val html = render("## 新機能\n\n### 細かい話\n")
        assertTrue(html, html.contains("<h2>新機能</h2>"))
        assertTrue(html, html.contains("<h3>細かい話</h3>"))
        assertFalse(html, html.contains("#"))
    }

    @Test
    fun 強調は太字タグになりアスタリスクが残らない() {
        val html = render("これは**重要**です。")
        assertTrue(html, html.contains("<strong>重要</strong>"))
        assertFalse(html, html.contains("*"))
    }

    // --- 箇条書き ---

    @Test
    fun 箇条書きは行頭記号と改行になる() {
        val html = render("- ひとつめ\n- ふたつめ\n")
        assertTrue(html, html.contains("・ひとつめ<br>"))
        assertTrue(html, html.contains("・ふたつめ<br>"))
    }

    @Test
    fun 箇条書きにulやliを使わない() {
        // <ul>/<li> に行頭記号と改行が付くのは API 24 以降。API 22 では全項目が1行に繋がる。
        val html = render("- ひとつめ\n- ふたつめ\n")
        assertFalse(html, html.contains("<ul"))
        assertFalse(html, html.contains("<li"))
    }

    @Test
    fun 番号付きリストは1から振り直す() {
        val html = render("3. みっつめ\n4. よっつめ\n")
        assertTrue(html, html.contains("1. みっつめ<br>"))
        assertTrue(html, html.contains("2. よっつめ<br>"))
        assertFalse(html, html.contains("<ol"))
    }

    // --- リンクと画像 ---

    @Test
    fun リンクは本文だけ残しURLを落とす() {
        val html = render("詳しくは[操作マニュアル](https://example.invalid/MANUAL.md)を参照してください。")
        assertTrue(html, html.contains("詳しくは操作マニュアルを参照してください。"))
        assertFalse(html, html.contains("<a"))
        assertFalse(html, html.contains("example.invalid"))
    }

    @Test
    fun 画像は出力に現れない() {
        // ImageGetter を渡さない Html は <img> に壊れた画像アイコンを出すため、丸ごと落とす。
        val html = render("![説明の図](https://example.invalid/shot.png)\n\n続きの本文。")
        assertFalse(html, html.contains("<img"))
        assertFalse(html, html.contains("example.invalid"))
        assertFalse(html, html.contains("説明の図"))
        assertTrue(html, html.contains("続きの本文。"))
    }

    // --- コード ---

    @Test
    fun インラインコードは等幅タグになりバッククォートが残らない() {
        val html = render("`epcltvapp://live/channelId/1` を開きます。")
        assertTrue(html, html.contains("<tt>epcltvapp://live/channelId/1</tt>"))
        assertFalse(html, html.contains("`"))
        assertFalse(html, html.contains("<code"))
    }

    @Test
    fun コードブロックは行ごとに改行される() {
        val html = render("```\n一行目\n二行目\n```\n")
        assertTrue(html, html.contains("一行目<br>二行目"))
        assertFalse(html, html.contains("<pre"))
        assertFalse(html, html.contains("```"))
    }

    // --- 表 ---

    @Test
    fun 表はパイプなしの行になり見出し行が太字になる() {
        val html = render(
            """
            | 項目 | 内容 |
            |---|---|
            | ひとつめ | 説明A |
            | ふたつめ | 説明B |
            """.trimIndent()
        )
        assertTrue(html, html.contains("<b>項目</b> — <b>内容</b><br>"))
        assertTrue(html, html.contains("ひとつめ — 説明A<br>"))
        assertTrue(html, html.contains("ふたつめ — 説明B<br>"))
        assertFalse(html, html.contains("|"))
        assertFalse(html, html.contains("<table"))
    }

    // --- エスケープ ---

    @Test
    fun HTMLとして解釈されては困る文字を実体参照にする() {
        val html = render("`<channelId>` と A&B を書く。")
        assertTrue(html, html.contains("&lt;channelId&gt;"))
        assertTrue(html, html.contains("&amp;"))
    }

    @Test
    fun escapeHtmlはアンパサンドを先に置き換える() {
        // 先に < を置き換えると &lt; の & がさらにエスケープされて &amp;lt; になってしまう。
        assertEquals("&amp;lt;", ReleaseNotesRenderer.escapeHtml("&lt;"))
        assertEquals("&lt;b&gt;", ReleaseNotesRenderer.escapeHtml("<b>"))
    }

    // --- リリースノート全体の形 ---

    @Test
    fun 見出しと本文と箇条書きが混ざっても記号が残らない() {
        val html = render(
            """
            ## 新機能

            ### 何かができるようになりました

            本文です。`コード` と [リンク](https://example.invalid/) を含みます。

            ## 不具合修正

            - **強調つきの項目** ある条件で起きていた不具合を直しました。
            - もうひとつの項目。
            """.trimIndent()
        )
        // Markdown の記号が生で残っていないこと
        for (mark in listOf("##", "**", "`", "](")) {
            assertFalse("$mark が残っている: $html", html.contains(mark))
        }
        assertTrue(html, html.contains("<h2>新機能</h2>"))
        assertTrue(html, html.contains("<h3>何かができるようになりました</h3>"))
        assertTrue(html, html.contains("・<strong>強調つきの項目</strong>"))
    }
}
