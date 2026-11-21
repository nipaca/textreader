package com.textreader.app.docs

import android.util.Xml
import com.textreader.app.text.Block
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads the text of a Word (.docx) file. Pictures are skipped; code-styled paragraphs become "Code here". */
object DocxParser {

    private val codeStyle = Regex("(?i)code|source|preformat|verbatim|listing|macro")
    private val monoFont = Regex("(?i)mono|courier|consolas|menlo|inconsolata|fira ?code|source code|lucida console")

    // Elements whose contents are not read: pictures, field codes, deleted text, comments.
    private val skipped = setOf(
        "w:drawing", "w:pict", "w:object", "w:instrText", "w:delText",
        "mc:AlternateContent", "w:commentReference", "w:footnoteReference", "w:endnoteReference",
    )

    fun parse(input: InputStream, emit: (Block) -> Unit) {
        val zip = ZipInputStream(input)
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "word/document.xml") {
                parseDocument(zip, emit)
                return
            }
            entry = zip.nextEntry
        }
        error("This doesn't look like a Word file.")
    }

    private fun parseDocument(stream: InputStream, emit: (Block) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, "UTF-8")

        val text = StringBuilder()
        var styleIsCode = false
        var runIsMono = false
        var monoChars = 0
        var allChars = 0
        var skipDepth = 0
        var inText = false
        var lastWasCode = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    if (skipDepth > 0) {
                        skipDepth++
                    } else if (name in skipped) {
                        skipDepth = 1
                    } else {
                        when (name) {
                            "w:p" -> {
                                text.setLength(0)
                                styleIsCode = false
                                monoChars = 0
                                allChars = 0
                            }
                            "w:pStyle" -> {
                                val style = parser.getAttributeValue(null, "w:val").orEmpty()
                                if (codeStyle.containsMatchIn(style)) styleIsCode = true
                            }
                            "w:r" -> runIsMono = false
                            "w:rFonts" -> {
                                val font = parser.getAttributeValue(null, "w:ascii")
                                    ?: parser.getAttributeValue(null, "w:hAnsi")
                                runIsMono = font != null && monoFont.containsMatchIn(font)
                            }
                            "w:t" -> inText = true
                            "w:tab", "w:br", "w:cr" -> text.append(' ')
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (skipDepth == 0 && inText) {
                        val t = parser.text.orEmpty()
                        text.append(t)
                        val visible = t.count { !it.isWhitespace() }
                        allChars += visible
                        if (runIsMono) monoChars += visible
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (skipDepth > 0) {
                        skipDepth--
                    } else {
                        when (parser.name) {
                            "w:t" -> inText = false
                            "w:p" -> {
                                val paragraph = text.toString().trim()
                                if (paragraph.isNotEmpty()) {
                                    val isCode = styleIsCode || (allChars >= 3 && monoChars >= allChars * 0.9)
                                    if (isCode) {
                                        if (!lastWasCode) emit(Block.Code)
                                        lastWasCode = true
                                    } else {
                                        emit(Block.Text(paragraph))
                                        lastWasCode = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
    }
}
