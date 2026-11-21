package com.textreader.app.docs

import android.net.Uri
import android.text.Html
import com.textreader.app.text.Block
import java.io.File
import java.util.zip.ZipFile

/** Reads an EPUB book chapter by chapter, in reading order. */
object EpubParser {

    fun parse(file: File, stillWanted: () -> Boolean, emit: (List<Block>) -> Unit) {
        ZipFile(file).use { zip ->
            val container = zip.readText("META-INF/container.xml") ?: error("This doesn't look like an EPUB book.")
            val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(container)?.groupValues?.get(1)
                ?: error("This EPUB book has no table of contents.")
            val opf = zip.readText(opfPath) ?: error("This EPUB book is damaged.")
            val baseDir = opfPath.substringBeforeLast('/', "")

            val manifest = HashMap<String, String>()
            Regex("<(?:\\w+:)?item\\b[^>]*>").findAll(opf).forEach { match ->
                val id = attribute(match.value, "id")
                val href = attribute(match.value, "href")
                if (id != null && href != null) manifest[id] = href
            }
            val spine = Regex("<(?:\\w+:)?itemref\\b[^>]*>").findAll(opf)
                .mapNotNull { attribute(it.value, "idref") }
                .toList()

            for (idref in spine) {
                if (!stillWanted()) return
                val href = manifest[idref] ?: continue
                val path = resolve(baseDir, Uri.decode(href.substringBefore('#')))
                val html = zip.readText(path) ?: continue
                val blocks = HtmlText.toBlocks(html)
                if (blocks.isNotEmpty()) emit(blocks)
            }
        }
    }

    private fun attribute(tag: String, name: String): String? =
        Regex("\\s$name\\s*=\\s*([\"'])(.*?)\\1").find(tag)?.groupValues?.get(2)

    private fun resolve(baseDir: String, href: String): String {
        val parts = ArrayList<String>()
        val combined = if (baseDir.isEmpty() || href.startsWith("/")) href.trimStart('/') else "$baseDir/$href"
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    private fun ZipFile.readText(path: String): String? {
        val entry = getEntry(path)
            ?: entries().asSequence().firstOrNull { it.name.equals(path, ignoreCase = true) }
            ?: return null
        return getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

/** Turns HTML into paragraphs. Pictures are dropped; <pre> blocks become "Code here". */
object HtmlText {
    private const val CODE_MARK = "\uE000CODE\uE000"
    private val head = Regex("(?is)<head\\b.*?</head\\s*>")
    private val dropped = Regex("(?is)<(script|style|svg|math|video|audio|object|iframe|noscript|template)\\b.*?</\\1\\s*>")
    private val pre = Regex("(?is)<pre\\b.*?</pre\\s*>")
    private val pictures = Regex("(?is)<(img|image|picture|source)\\b[^>]*>")

    fun toBlocks(html: String): List<Block> {
        val cleaned = html
            .replace(head, "")
            .replace(dropped, "")
            .replace(pre, "<p>$CODE_MARK</p>")
            .replace(pictures, "")
        val plain = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
        val out = ArrayList<Block>()
        for (paragraph in plain.split(Regex("\\n\\s*\\n"))) {
            val text = paragraph.replace('\n', ' ').trim()
            when {
                text.contains(CODE_MARK) -> if (out.lastOrNull() !is Block.Code) out += Block.Code
                text.any { it.isLetter() } -> out += Block.Text(text)
            }
        }
        return out
    }
}
