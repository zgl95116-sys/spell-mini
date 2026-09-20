package com.logan.spellmini.ui

/**
 * Just enough Markdown for the pages the assistant hands in: headings, emphasis, links, lists, tables, quotes, rules
 * and code. Everything is escaped first and the page is shown with JavaScript off, so whatever a web page managed to
 * smuggle into the text stays text. A library would do more; this is about a hundred lines and adds no dependency.
 */
object Markdown {
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val LINK = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")
    private val BARE = Regex("""(?<!["=>])(https?://[^\s<）)】」，。；、]+)""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""")
    private val ITALIC = Regex("""(?<!\*)\*(?!\*)([^*\n]+?)\*(?!\*)""")
    private val CODE = Regex("""`([^`]+)`""")

    private fun inline(raw: String): String {
        var text = escape(raw)
        text = CODE.replace(text) { "<code>${it.groupValues[1]}</code>" }
        text = LINK.replace(text) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        text = BARE.replace(text) { "<a href=\"${it.groupValues[1]}\">${it.groupValues[1].removePrefix("https://").removePrefix("http://").take(40)}</a>" }
        text = BOLD.replace(text) { "<b>${it.groupValues[1]}</b>" }
        return ITALIC.replace(text) { "<i>${it.groupValues[1]}</i>" }
    }

    private fun cells(line: String) = line.trim().trim('|').split('|').map { it.trim() }
    private fun isRow(line: String) = line.trim().startsWith("|") && line.trim().endsWith("|") && line.count { it == '|' } >= 2
    private fun isDivider(line: String) = isRow(line) && cells(line).all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' || it == ' ' } }

    fun toHtml(markdown: String): String {
        val out = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").lines()
        var i = 0
        var list: String? = null
        fun closeList() { list?.let { out.append("</$it>") }; list = null }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    closeList()
                    val code = StringBuilder()
                    i += 1
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.append(escape(lines[i])).append('\n'); i += 1 }
                    out.append("<pre>").append(code).append("</pre>")
                }
                isRow(trimmed) && i + 1 < lines.size && isDivider(lines[i + 1]) -> {
                    closeList()
                    out.append("<div class=\"scroll\"><table><tr>")
                    cells(trimmed).forEach { out.append("<th>").append(inline(it)).append("</th>") }
                    out.append("</tr>")
                    i += 2
                    while (i < lines.size && isRow(lines[i])) {
                        out.append("<tr>")
                        cells(lines[i]).forEach { out.append("<td>").append(inline(it)).append("</td>") }
                        out.append("</tr>")
                        i += 1
                    }
                    out.append("</table></div>")
                    continue
                }
                trimmed.isEmpty() -> closeList()
                Regex("^#{1,6}\\s").containsMatchIn(trimmed) -> {
                    closeList()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(4)
                    out.append("<h$level>").append(inline(trimmed.drop(level).trim())).append("</h$level>")
                }
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed) -> { closeList(); out.append("<hr>") }
                trimmed.startsWith(">") -> { closeList(); out.append("<blockquote>").append(inline(trimmed.trimStart('>', ' '))).append("</blockquote>") }
                Regex("^[-*+]\\s+").containsMatchIn(trimmed) -> {
                    if (list != "ul") { closeList(); out.append("<ul>"); list = "ul" }
                    out.append("<li>").append(inline(trimmed.replaceFirst(Regex("^[-*+]\\s+"), ""))).append("</li>")
                }
                Regex("^\\d+[.)、]\\s*").containsMatchIn(trimmed) -> {
                    if (list != "ol") { closeList(); out.append("<ol>"); list = "ol" }
                    out.append("<li>").append(inline(trimmed.replaceFirst(Regex("^\\d+[.)、]\\s*"), ""))).append("</li>")
                }
                else -> { closeList(); out.append("<p>").append(inline(trimmed)).append("</p>") }
            }
            i += 1
        }
        closeList()
        return out.toString()
    }

    fun page(title: String, markdown: String): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { font: 16px/1.75 -apple-system, "Noto Sans CJK SC", sans-serif; color: #2C2C2E; margin: 0; padding: 8px 20px 48px; word-break: break-word; }
          h1 { font-size: 24px; line-height: 1.35; margin: 18px 0 10px; color: #1C1C1E; }
          h2 { font-size: 19px; margin: 28px 0 8px; color: #1C1C1E; }
          h3, h4 { font-size: 16px; margin: 20px 0 6px; color: #1C1C1E; }
          p { margin: 8px 0; } ul, ol { padding-left: 22px; margin: 8px 0; } li { margin: 4px 0; }
          a { color: #2F6FED; text-decoration: none; }
          .scroll { overflow-x: auto; margin: 12px 0; }
          table { border-collapse: collapse; font-size: 14px; line-height: 1.5; min-width: 100%; }
          th, td { border: 1px solid #E9E9EC; padding: 7px 9px; text-align: left; vertical-align: top; }
          th { background: #F2F2F4; font-weight: 600; white-space: nowrap; }
          td:first-child { white-space: nowrap; }
          blockquote { margin: 10px 0; padding: 4px 14px; border-left: 3px solid #C7C7CC; color: #6B6B70; }
          code, pre { background: #F2F2F4; border-radius: 6px; font-size: 14px; } code { padding: 1px 5px; } pre { padding: 12px; overflow-x: auto; }
          hr { border: 0; border-top: 1px solid #E9E9EC; margin: 22px 0; }
        </style><title>${escape(title)}</title></head><body>${toHtml(markdown)}</body></html>
    """.trimIndent()
}
