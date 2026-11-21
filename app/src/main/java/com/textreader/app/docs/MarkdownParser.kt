package com.textreader.app.docs

import com.textreader.app.text.Block

/** Reads Markdown as plain speech: no symbols read aloud, code blocks become "Code here". */
object MarkdownParser {

    private val fence = Regex("^\\s{0,3}(```+|~~~+)")
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^\\s*(?:[-*+]|\\d{1,3}[.)])\\s+(.*)$")
    private val quote = Regex("^\\s*(?:>\\s?)+")
    private val rule = Regex("^\\s{0,3}(?:(?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,})$")
    private val setextUnderline = Regex("^\\s{0,3}=+\\s*$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")
    private val referenceDefinition = Regex("^\\s{0,3}\\[[^\\]]+]:\\s*\\S+.*$")
    private val htmlComment = Regex("<!--[\\s\\S]*?-->")

    private val image = Regex("!\\[[^\\]]*]\\([^)]*\\)|!\\[[^\\]]*]\\[[^\\]]*]")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val referenceLink = Regex("\\[([^\\]]+)]\\[[^\\]]*]")
    private val autoLink = Regex("<((?:https?|ftp)://[^>\\s]+)>")
    private val inlineCode = Regex("`+([^`]+?)`+")
    private val bold = Regex("(\\*\\*|__)(.+?)\\1")
    private val italic = Regex("(?<![\\w*])[*_](\\S(?:.*?\\S)?)[*_](?![\\w*])")
    private val strike = Regex("~~(.+?)~~")
    private val htmlTag = Regex("</?[a-zA-Z][^>]*>")
    private val escaped = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>|])")

    fun parse(source: String): List<Block> {
        val text = source.replace("\r\n", "\n").replace('\r', '\n').replace(htmlComment, "")
        val out = ArrayList<Block>()
        val paragraph = StringBuilder()

        fun flush() {
            val inline = inline(paragraph.toString()).trim()
            if (inline.isNotEmpty()) out += Block.Text(inline)
            paragraph.setLength(0)
        }

        fun addCode() {
            flush()
            if (out.lastOrNull() !is Block.Code) out += Block.Code
        }

        var inFence = false
        var fenceMarker = ""
        for (rawLine in text.lines()) {
            if (inFence) {
                if (rawLine.trimStart().startsWith(fenceMarker)) inFence = false
                continue
            }
            val fenceMatch = fence.find(rawLine)
            if (fenceMatch != null) {
                fenceMarker = fenceMatch.groupValues[1]
                inFence = true
                addCode()
                continue
            }
            if (rawLine.isBlank()) {
                flush()
                continue
            }
            if (rule.matches(rawLine) || setextUnderline.matches(rawLine) ||
                tableSeparator.matches(rawLine) || referenceDefinition.matches(rawLine)
            ) {
                flush()
                continue
            }
            val line = rawLine.replace(quote, "")
            val headingMatch = heading.matchEntire(line)
            if (headingMatch != null) {
                flush()
                paragraph.append(headingMatch.groupValues[1])
                flush()
                continue
            }
            val bulletMatch = bullet.matchEntire(line)
            if (bulletMatch != null) {
                flush()
                paragraph.append(bulletMatch.groupValues[1])
                continue
            }
            if (line.count { it == '|' } >= 2) {
                // A table row: read the cells separated by commas.
                flush()
                paragraph.append(line.trim().trim('|').split('|').joinToString(", ") { it.trim() })
                flush()
                continue
            }
            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(line.trim())
        }
        flush()
        return out
    }

    private fun inline(input: String): String {
        var s = input
        s = s.replace(image, "")
        s = s.replace(autoLink) { it.groupValues[1] }
        s = s.replace(link) { it.groupValues[1] }
        s = s.replace(referenceLink) { it.groupValues[1] }
        s = s.replace(inlineCode) { it.groupValues[1] }
        s = s.replace(bold) { it.groupValues[2] }
        s = s.replace(strike) { it.groupValues[1] }
        s = s.replace(italic) { it.groupValues[1] }
        s = s.replace(htmlTag, "")
        s = s.replace(escaped) { it.groupValues[1] }
        return s
    }
}
