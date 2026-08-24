package com.daigorian.epcltvapp

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer

/**
 * リリースノートの Markdown を、TextView に流し込める HTML へ変換する。
 *
 * 解釈は commonmark-java に任せる。**「今のリリースノートが使っている記法だけ」を自前で
 * 変換する作りにはしない。** 将来どんな記法が使われるか分からず、取りこぼすと `##` や `**` が
 * 画面に生のまま出てしまうため。
 *
 * 変換結果は [androidx.core.text.HtmlCompat.fromHtml] に渡す。ただし Android の
 * [android.text.Html] が解釈できるタグは限られているので、**そのままでは崩れるものだけ
 * 出力を差し替えている**（[TvNodeRenderer]）。見出し・強調・引用・段落は既定の出力で足りる。
 */
object ReleaseNotesRenderer {

    private val parser: Parser = Parser.builder()
        // 表を Table ノードとして解釈させる。入れないと "| a | b |" がただの段落になり、
        // パイプが画面にそのまま出る。
        .extensions(listOf(TablesExtension.create()))
        .build()

    // レンダラ側には TablesExtension を入れない。入れると拡張の <table> 出力と
    // [TvNodeRenderer] の出力のどちらが優先されるかが登録順に依存してしまう。
    // 登録しなければ Table ノードを描けるのは [TvNodeRenderer] だけになり、順序に依存しない。
    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .nodeRendererFactory { context -> TvNodeRenderer(context) }
        .build()

    /** [markdown] を HTML の断片にする。null や空なら空文字。 */
    fun toHtml(markdown: String?): String {
        val source = markdown?.trim().orEmpty()
        if (source.isEmpty()) return ""
        return renderer.render(parser.parse(source)).trim()
    }

    /** HTML として解釈されては困る文字を実体参照にする。`&` を最初に置き換えること。 */
    fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

/**
 * Android の [android.text.Html] で崩れるノードだけ、出力を差し替えるレンダラ。
 *
 * | ノード | 差し替える理由 |
 * |---|---|
 * | 箇条書き・番号付きリスト | `<ul>`/`<li>` に行頭記号と改行が付くのは API 24 以降。API 22 では全項目が1行に繋がってしまう |
 * | 表 | `<table>` を解釈しないため、セルが区切りなしで繋がって読めなくなる |
 * | コード | `<code>`/`<pre>` を解釈しない。等幅にするには `<tt>` が要る |
 * | 画像 | `ImageGetter` を渡さない `Html` は、壊れた画像アイコンを出す |
 * | リンク | 押せないのに下線付きの見た目になり誤解を招く。本文だけ残す |
 *
 * リストと表を自前で組み立てているのは、**minSdk 22 から Android 14 まで同じ見た目にする**
 * ためでもある。API レベルによって行が繋がったり繋がらなかったりするのは避けたい。
 */
private class TvNodeRenderer(private val context: HtmlNodeRendererContext) : NodeRenderer {

    private val html = context.writer

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(
        Image::class.java,
        Link::class.java,
        Code::class.java,
        FencedCodeBlock::class.java,
        IndentedCodeBlock::class.java,
        BulletList::class.java,
        OrderedList::class.java,
        ListItem::class.java,
        TableBlock::class.java,
        TableHead::class.java,
        TableBody::class.java,
        TableRow::class.java,
        TableCell::class.java
    )

    override fun render(node: Node) {
        when (node) {
            is Image -> Unit
            is Link -> renderChildren(node)
            is Code -> renderMonospace(node.literal)
            is FencedCodeBlock -> renderCodeBlock(node.literal)
            is IndentedCodeBlock -> renderCodeBlock(node.literal)
            is BulletList -> renderList(node, ordered = false)
            is OrderedList -> renderList(node, ordered = true)
            // リストの外に単独で現れた項目。通常は renderList 側で処理される。
            is ListItem -> {
                renderChildren(node)
                html.raw(LINE_BREAK)
            }
            is TableBlock -> renderChildren(node)
            is TableHead -> renderChildren(node)
            is TableBody -> renderChildren(node)
            is TableRow -> renderTableRow(node)
            is TableCell -> renderTableCell(node)
        }
    }

    /**
     * 行頭記号を文字として置き、項目ごとに改行を入れる。
     *
     * 番号は開始番号によらず必ず 1 から振る。開始番号を取り出す API は
     * commonmark-java のバージョンによって名前が変わっており、リリースノートで
     * 途中から始まる番号付きリストを使う見込みもないため、依存しない。
     */
    private fun renderList(list: Node, ordered: Boolean) {
        // ネストしたリストは、そのままだと親項目の本文と地続きになる。
        if (list.parent is ListItem) html.raw(LINE_BREAK)

        var item = list.firstChild
        var number = 1
        while (item != null) {
            val next = item.next
            html.raw(if (ordered) "$number. " else BULLET_MARK)
            renderChildren(item)
            html.raw(LINE_BREAK)
            number++
            item = next
        }
    }

    /** `| a | b |` を `a — b` の1行にする。見出し行のセルは太字にする。 */
    private fun renderTableRow(row: TableRow) {
        var cell = row.firstChild
        var first = true
        while (cell != null) {
            val next = cell.next
            if (!first) html.raw(CELL_SEPARATOR)
            context.render(cell)
            first = false
            cell = next
        }
        html.raw(LINE_BREAK)
    }

    private fun renderTableCell(cell: TableCell) {
        if (cell.isHeader) html.tag("b")
        renderChildren(cell)
        if (cell.isHeader) html.tag("/b")
    }

    private fun renderMonospace(literal: String) {
        html.tag("tt")
        html.text(literal)
        html.tag("/tt")
    }

    /**
     * コードブロック。行頭の空白は HTML でまとめられてしまうため、字下げは保てない。
     * 行が繋がってしまうよりはましという判断で、改行だけ保つ。
     */
    private fun renderCodeBlock(literal: String) {
        html.tag("tt")
        literal.trimEnd('\n').split("\n").forEachIndexed { index, line ->
            if (index > 0) html.raw(LINE_BREAK)
            html.text(line)
        }
        html.tag("/tt")
        html.raw(LINE_BREAK)
    }

    private fun renderChildren(parent: Node) {
        var child = parent.firstChild
        while (child != null) {
            val next = child.next
            context.render(child)
            child = next
        }
    }

    companion object {
        private const val LINE_BREAK = "<br>"
        private const val BULLET_MARK = "・"
        private const val CELL_SEPARATOR = " — "
    }
}
