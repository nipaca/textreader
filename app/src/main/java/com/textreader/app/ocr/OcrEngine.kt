package com.textreader.app.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.textreader.app.text.Block
import java.io.File

/** Offline text recognition (Tesseract) for scanned PDF pages and image-only screens. */
object OcrEngine {

    private val lock = Any()
    private var api: TessBaseAPI? = null

    fun recognize(context: Context, bitmap: Bitmap): String = synchronized(lock) {
        val tess = api ?: create(context).also { api = it }
        tess.setImage(bitmap)
        val text = tess.getUTF8Text().orEmpty()
        tess.clear()
        text
    }

    private fun create(context: Context): TessBaseAPI {
        val base = File(context.filesDir, "tesseract")
        val tessdata = File(base, "tessdata").apply { mkdirs() }
        val data = File(tessdata, "eng.traineddata")
        if (!data.isFile || data.length() == 0L) {
            context.assets.open("tessdata/eng.traineddata").use { input ->
                data.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val tess = TessBaseAPI()
        if (!tess.init(base.absolutePath, "eng")) {
            tess.recycle()
            error("Text recognition could not start")
        }
        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        return tess
    }

    /** Turns recognized text into paragraphs: blank lines split paragraphs, wrapped lines are joined. */
    fun toBlocks(text: String): List<Block> =
        text.split(Regex("\\n\\s*\\n"))
            .map { joinLines(it) }
            .filter { it.any(Char::isLetter) }
            .map { Block.Text(it) }

    fun joinLines(paragraph: String): String {
        val sb = StringBuilder()
        for (rawLine in paragraph.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (sb.isNotEmpty()) {
                if (sb.endsWith("-") && sb.length > 1 && sb[sb.length - 2].isLetter() && line.first().isLowerCase()) {
                    sb.setLength(sb.length - 1) // re-join a word split across lines
                } else {
                    sb.append(' ')
                }
            }
            sb.append(line)
        }
        return sb.toString()
    }
}
