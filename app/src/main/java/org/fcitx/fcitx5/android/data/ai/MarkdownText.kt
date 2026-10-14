/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Turns the Markdown that LLMs like to answer in into readable text.
 * The result's plain string (toString()) has no Markdown markers left: lists become "• ",
 * headings / bold / code lose their symbols, tables become "a | b" rows.
 * Its spans (bold, italic, heading size, monospace, strikethrough, links) give the rich preview.
 */
package org.fcitx.fcitx5.android.data.ai

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

object MarkdownText {

    private val fence = Regex("""^\s*(```|~~~)""")
    private val heading = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val quote = Regex("""^\s{0,3}>\s?(.*)$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val ordered = Regex("""^(\s*)(\d{1,9})([.)])\s+(.*)$""")
    private val task = Regex("""^\[([ xX])]\s+(.*)$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private val br = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    /** Rendered text; call toString() for the plain version. */
    fun render(md: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        val lines = md.replace("\r\n", "\n").replace(br, "\n").split('\n')
        var blank = 0
        var i = 0

        fun newLine() {
            if (out.isNotEmpty()) out.append('\n')
        }

        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                blank++; i++; continue
            }
            // collapse runs of blank lines into one empty line
            if (blank > 0 && out.isNotEmpty()) out.append('\n')
            blank = 0
            newLine()

            val f = fence.find(line)
            if (f != null) {
                val marker = f.groupValues[1]
                val start = out.length
                i++
                val code = mutableListOf<String>()
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) code.add(lines[i++])
                i++ // closing fence
                out.append(code.joinToString("\n").trimEnd())
                span(out, TypefaceSpan("monospace"), start)
                continue
            }

            val h = heading.matchEntire(line)
            if (h != null) {
                val level = h.groupValues[1].length
                val start = out.length
                inline(h.groupValues[2], out)
                span(out, StyleSpan(Typeface.BOLD), start)
                val size = when (level) { 1 -> 1.3f; 2 -> 1.2f; 3 -> 1.1f; else -> 1f }
                if (size != 1f) span(out, RelativeSizeSpan(size), start)
                i++; continue
            }

            if (rule.matches(line)) {
                out.append("──────────"); i++; continue
            }

            val q = quote.matchEntire(line)
            if (q != null) {
                out.append("│ ")
                val start = out.length
                inline(q.groupValues[1], out)
                span(out, StyleSpan(Typeface.ITALIC), start)
                i++; continue
            }

            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                // header row, separator, then body rows
                tableRow(line, out, header = true)
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    out.append('\n'); tableRow(lines[i], out, header = false); i++
                }
                continue
            }

            val b = bullet.matchEntire(line)
            if (b != null) {
                out.append(indent(b.groupValues[1]))
                val body = b.groupValues[2]
                val t = task.matchEntire(body)
                if (t != null) {
                    out.append(if (t.groupValues[1] == " ") "☐ " else "☑ ")
                    inline(t.groupValues[2], out)
                } else {
                    out.append("• "); inline(body, out)
                }
                i++; continue
            }

            val o = ordered.matchEntire(line)
            if (o != null) {
                out.append(indent(o.groupValues[1])).append(o.groupValues[2]).append(". ")
                inline(o.groupValues[4], out)
                i++; continue
            }

            inline(line.trim(), out)
            i++
        }
        return out
    }

    fun toPlain(md: String) = render(md).toString()

    private fun indent(ws: String): String {
        val n = ws.replace("\t", "    ").length / 2
        return "  ".repeat(n.coerceAtMost(4))
    }

    private fun tableRow(line: String, out: SpannableStringBuilder, header: Boolean) {
        var s = line.trim()
        if (s.startsWith('|')) s = s.substring(1)
        if (s.endsWith('|') && !s.endsWith("\\|")) s = s.dropLast(1)
        val cells = s.split(Regex("""(?<!\\)\|""")).map { it.trim() }
        val start = out.length
        cells.forEachIndexed { k, c ->
            if (k > 0) out.append(" | ")
            inline(c, out)
        }
        if (header) span(out, StyleSpan(Typeface.BOLD), start)
    }

    private fun span(out: SpannableStringBuilder, what: Any, start: Int) {
        if (out.length > start) out.setSpan(what, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun isWordChar(c: Char?) = c != null && c.isLetterOrDigit() && c.code < 0x2E80

    /** Inline Markdown: escapes, `code`, **bold**, *italic*, ~~strike~~, [links](url), ![images](url). */
    private fun inline(s: String, out: SpannableStringBuilder) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val prev = if (i > 0) s[i - 1] else null

            if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!|~<>") {
                out.append(s[i + 1]); i += 2; continue
            }

            if (c == '`') {
                var n = 0
                while (i + n < s.length && s[i + n] == '`') n++
                val ticks = "`".repeat(n)
                val end = s.indexOf(ticks, i + n)
                if (end > i + n) {
                    val start = out.length
                    out.append(s.substring(i + n, end).trim())
                    span(out, TypefaceSpan("monospace"), start)
                    i = end + n; continue
                }
                out.append(ticks); i += n; continue
            }

            if ((c == '*' || c == '_' || c == '~') && i + 1 < s.length && s[i + 1] == c) {
                val delim = "$c$c"
                val end = s.indexOf(delim, i + 2)
                val ok = end > i + 2 && !s[i + 2].isWhitespace() && !s[end - 1].isWhitespace() &&
                        (c != '_' || (!isWordChar(prev) && !isWordChar(s.getOrNull(end + 2))))
                if (ok) {
                    val start = out.length
                    inline(s.substring(i + 2, end), out)
                    span(out, if (c == '~') StrikethroughSpan() else StyleSpan(Typeface.BOLD), start)
                    i = end + 2; continue
                }
            }

            if ((c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != c &&
                (c != '_' || !isWordChar(prev))
            ) {
                var end = i + 1
                while (true) {
                    end = s.indexOf(c, end)
                    if (end < 0) break
                    // skip a doubled delimiter inside, e.g. *a **b** c*
                    if (end + 1 < s.length && s[end + 1] == c) { end += 2; continue }
                    break
                }
                if (end > i + 1 && !s[end - 1].isWhitespace() &&
                    (c != '_' || !isWordChar(s.getOrNull(end + 1)))
                ) {
                    val start = out.length
                    inline(s.substring(i + 1, end), out)
                    span(out, StyleSpan(Typeface.ITALIC), start)
                    i = end + 1; continue
                }
            }

            if (c == '[' || (c == '!' && s.getOrNull(i + 1) == '[')) {
                val image = c == '!'
                val open = if (image) i + 1 else i
                val close = s.indexOf("](", open + 1)
                val urlEnd = if (close > 0) s.indexOf(')', close + 2) else -1
                if (close > open && urlEnd > close && !s.substring(open + 1, close).contains('[')) {
                    val label = s.substring(open + 1, close)
                    val url = s.substring(close + 2, urlEnd).trim().substringBefore(' ')
                    val start = out.length
                    if (image) {
                        inline(label, out)
                    } else if (label.isBlank() || label == url) {
                        out.append(url)
                        span(out, URLSpan(url), start)
                    } else {
                        inline(label, out)
                        span(out, URLSpan(url), start)
                        out.append(" (").append(url).append(")")
                    }
                    i = urlEnd + 1; continue
                }
            }

            out.append(c); i++
        }
    }
}
