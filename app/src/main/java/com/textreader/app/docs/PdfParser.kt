package com.textreader.app.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.textreader.app.ocr.OcrEngine
import com.textreader.app.text.Block
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * Reads a PDF page by page. Normal pages use the PDF's own text; scanned pages (no text)
 * are read with offline OCR. Pictures are skipped; lines in a code font become "Code here".
 */
class PdfParser(private val context: Context) {

    fun parse(
        file: File,
        stillWanted: () -> Boolean,
        onProgress: (String) -> Unit,
        emit: (List<Block>) -> Unit,
    ) {
        PDDocument.load(file).use { document ->
            val pageCount = document.numberOfPages
            val stripper = CodeAwareStripper()
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                for (page in 1..pageCount) {
                    if (!stillWanted()) return
                    if (page > 1) onProgress("Preparing page $page of $pageCount…")
                    stripper.startPage = page
                    stripper.endPage = page
                    var blocks = pageBlocks(stripper.getText(document))
                    val textLength = blocks.sumOf { (it as? Block.Text)?.text?.length ?: 0 }
                    if (textLength < 20 && blocks.none { it is Block.Code }) {
                        // Probably a scanned page: read it from the page image.
                        onProgress("Reading scanned page $page of $pageCount…")
                        val pageRenderer: PdfRenderer = renderer ?: run {
                            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            descriptor = fd
                            PdfRenderer(fd).also { renderer = it }
                        }
                        blocks = runCatching { ocrPage(pageRenderer, page - 1) }.getOrDefault(emptyList())
                    }
                    if (blocks.isNotEmpty()) emit(blocks)
                }
            } finally {
                runCatching { renderer?.close() }
                runCatching { descriptor?.close() }
            }
        }
    }

    private fun pageBlocks(pageText: String): List<Block> {
        val out = ArrayList<Block>()
        for (paragraph in pageText.split(Regex("\\n\\s*\\n"))) {
            val lines = StringBuilder()
            for (line in paragraph.lines()) {
                if (line.trim() == CODE_LINE) {
                    if (lines.isNotBlank()) out += Block.Text(OcrEngine.joinLines(lines.toString()))
                    lines.setLength(0)
                    if (out.lastOrNull() !is Block.Code) out += Block.Code
                } else {
                    lines.append(line).append('\n')
                }
            }
            if (lines.isNotBlank()) {
                val text = OcrEngine.joinLines(lines.toString())
                if (text.any { it.isLetter() }) out += Block.Text(text)
            }
        }
        return out
    }

    private fun ocrPage(renderer: PdfRenderer, index: Int): List<Block> {
        renderer.openPage(index).use { page ->
            val scale = 2000f / maxOf(page.width, page.height).coerceAtLeast(1)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val text = OcrEngine.recognize(context, bitmap)
            bitmap.recycle()
            return OcrEngine.toBlocks(text)
        }
    }

    /** Text extraction that marks lines written entirely in a monospace (code) font. */
    private class CodeAwareStripper : PDFTextStripper() {
        private val line = StringBuilder()
        private var monoChars = 0
        private var allChars = 0

        init {
            setSortByPosition(true)
            setParagraphEnd("\n\n")
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            line.append(text)
            for (position in textPositions) {
                val unicode = position.unicode ?: continue
                if (unicode.isBlank()) continue
                allChars++
                if (isMonospace(position)) monoChars++
            }
        }

        override fun writeWordSeparator() {
            line.append(' ')
        }

        override fun writeLineSeparator() {
            flushLine()
            super.writeLineSeparator()
        }

        override fun writeParagraphEnd() {
            flushLine()
            super.writeParagraphEnd()
        }

        override fun writePageEnd() {
            flushLine()
            super.writePageEnd()
        }

        private fun flushLine() {
            if (line.isNotEmpty()) {
                val isCode = allChars >= 3 && monoChars >= allChars * 0.9
                super.writeString(if (isCode) CODE_LINE else line.toString())
            }
            line.setLength(0)
            monoChars = 0
            allChars = 0
        }

        private fun isMonospace(position: TextPosition): Boolean {
            val font = position.font ?: return false
            if (font.fontDescriptor?.isFixedPitch == true) return true
            val name = font.name ?: return false
            return monoName.containsMatchIn(name)
        }
    }

    companion object {
        private const val CODE_LINE = "CODE"
        private val monoName = Regex("(?i)mono|courier|consolas|menlo|inconsolata|firacode|sourcecode|lucidaconsole")
    }
}
