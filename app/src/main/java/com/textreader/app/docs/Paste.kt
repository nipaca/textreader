package com.textreader.app.docs

import com.textreader.app.player.Reader
import com.textreader.app.player.Source
import com.textreader.app.text.Block

/** Loads copied text into the reader without starting it (it waits for Play). */
object Paste {

    private const val PREVIEW_LENGTH = 70

    /** Returns false when there's nothing readable to paste. */
    fun load(reader: Reader, text: String?): Boolean {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return false
        // AI apps copy answers as Markdown; this also handles plain text fine.
        val blocks = MarkdownParser.parse(raw)
        if (blocks.none { it is Block.Text }) return false

        val firstText = blocks.filterIsInstance<Block.Text>().joinToString(" ") { it.text }
            .replace(Regex("\\s+"), " ").trim()
        val preview = if (firstText.length > PREVIEW_LENGTH) firstText.take(PREVIEW_LENGTH).trimEnd() + "…" else firstText

        val token = reader.startNew("Pasted text", Source.CLIPBOARD, autoPlay = false, preview = "“$preview”")
        reader.append(token, blocks)
        reader.finish(token)
        return true
    }
}
