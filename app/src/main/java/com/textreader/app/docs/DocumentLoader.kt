package com.textreader.app.docs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.textreader.app.app
import com.textreader.app.player.Source
import com.textreader.app.text.Block
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Opens PDF, TXT, MD, DOCX and EPUB files and feeds their text to the reader, a batch at a time. */
object DocumentLoader {

    enum class Kind { PDF, TEXT, MARKDOWN, WORD, EPUB }

    /** What the file picker shows. */
    val MIME_TYPES = arrayOf(
        "application/pdf",
        "text/plain",
        "text/markdown",
        "text/x-markdown",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/epub+zip",
        "application/octet-stream",
    )

    fun open(context: Context, uri: Uri) {
        val app = context.app
        val reader = app.reader
        val name = displayName(context, uri)
        val kind = detect(name, context.contentResolver.getType(uri))
        if (kind == null) {
            reader.showMessage("This file type isn't supported. Use PDF, TXT, MD, DOCX or EPUB.")
            return
        }
        val title = name.substringBeforeLast('.').ifBlank { name }
        val token = reader.startNew(title, Source.FILE)
        val appContext = context.applicationContext

        app.appScope.launch(Dispatchers.IO) {
            val batch = Batcher { blocks -> reader.append(token, blocks) }
            val stillWanted = { reader.isCurrent(token) }
            try {
                when (kind) {
                    Kind.TEXT -> PlainText.parse(readText(appContext, uri)).forEach { if (stillWanted()) batch.add(it) }
                    Kind.MARKDOWN -> MarkdownParser.parse(readText(appContext, uri)).forEach { if (stillWanted()) batch.add(it) }
                    Kind.WORD -> appContext.contentResolver.openInputStream(uri)?.use { input ->
                        DocxParser.parse(input) { block -> if (stillWanted()) batch.add(block) }
                    } ?: error("Can't open the file.")
                    Kind.EPUB -> {
                        val file = copyToCache(appContext, uri, "book.epub")
                        EpubParser.parse(file, stillWanted) { blocks ->
                            batch.addAll(blocks)
                            batch.flush()
                        }
                    }
                    Kind.PDF -> {
                        val file = copyToCache(appContext, uri, "document.pdf")
                        PdfParser(appContext).parse(file, stillWanted, onProgress = { reader.showMessage(it) }) { blocks ->
                            reader.showMessage(null)
                            batch.addAll(blocks)
                            batch.flush()
                        }
                    }
                }
                batch.flush()
                reader.finish(token, "No readable text found in this file.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                batch.flush()
                reader.finish(token, friendlyError(e))
                if (reader.isCurrent(token)) reader.showMessage(friendlyError(e))
            }
        }
    }

    fun detect(name: String, mime: String?): Kind? {
        when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> return Kind.PDF
            "txt", "text", "log" -> return Kind.TEXT
            "md", "markdown", "mdown", "mkd" -> return Kind.MARKDOWN
            "docx" -> return Kind.WORD
            "epub" -> return Kind.EPUB
        }
        return when (mime) {
            "application/pdf" -> Kind.PDF
            "text/markdown", "text/x-markdown" -> Kind.MARKDOWN
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Kind.WORD
            "application/epub+zip" -> Kind.EPUB
            else -> if (mime?.startsWith("text/") == true) Kind.TEXT else null
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
    }

    private fun copyToCache(context: Context, uri: Uri, name: String): File {
        val file = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Can't open the file.")
        return file
    }

    /** Reads a text file, guessing the encoding (UTF-8 / UTF-16 / Windows-1252). */
    fun readText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Can't open the file.")
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    private fun friendlyError(e: Throwable): String {
        val name = e.javaClass.simpleName
        return when {
            name.contains("Password", ignoreCase = true) -> "This file is password-protected."
            e is OutOfMemoryError -> "This file is too big to open."
            else -> "Couldn't read this file${e.message?.let { ": $it" } ?: "."}"
        }
    }

    /** Sends text to the reader in batches so reading can start before the whole file is done. */
    private class Batcher(private val emit: (List<Block>) -> Unit) {
        private val pending = ArrayList<Block>()
        private var sent = 0

        fun add(block: Block) {
            pending += block
            // Send the first few paragraphs immediately, then in bigger groups.
            val limit = if (sent == 0) 3 else 40
            if (pending.size >= limit) flush()
        }

        fun addAll(blocks: List<Block>) = blocks.forEach { pending += it }

        fun flush() {
            if (pending.isEmpty()) return
            emit(ArrayList(pending))
            sent += pending.size
            pending.clear()
        }
    }
}

/** Plain text: blank lines separate paragraphs; wrapped lines are joined. */
object PlainText {
    fun parse(text: String): List<Block> =
        text.replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\\n\\s*\\n"))
            .map { para -> para.lines().joinToString(" ") { it.trim() }.trim() }
            .filter { it.isNotEmpty() }
            .map { Block.Text(it) }
}
