package com.textreader.app.text

import java.text.BreakIterator
import java.util.Locale

/** A piece of content to read: a paragraph of text, or a block of code (spoken as "Code here"). */
sealed interface Block {
    data class Text(val text: String) : Block
    data object Code : Block
}

/** One spoken unit (about one sentence). The seek bar moves between these. */
data class Segment(val text: String, val isCode: Boolean, val endsParagraph: Boolean)

object TextCleaner {
    // A full web address -> spoken as "link". Trailing punctuation stays with the sentence.
    private val url = Regex("""(?i)\b(?:https?://|ftp://|www\.)[^\s<>"']*[^\s<>"'.,;:!?)\]}]""")
    private val invisible = Regex("[​‌‍⁠﻿­￼�]")
    private val spaces = Regex("\\s+")

    fun clean(text: String): String =
        text.replace(invisible, "").replace(url, "link").replace(spaces, " ").trim()

    fun hasWords(text: String): Boolean = text.any { it.isLetter() }
}

object Segmenter {
    private const val MAX_LEN = 260
    const val CODE_SPEECH = "Code here."

    fun toSegments(blocks: List<Block>): List<Segment> {
        val out = ArrayList<Segment>()
        for (block in blocks) {
            when (block) {
                is Block.Code -> if (out.lastOrNull()?.isCode != true) out += Segment(CODE_SPEECH, isCode = true, endsParagraph = true)
                is Block.Text -> {
                    val text = TextCleaner.clean(block.text)
                    if (!TextCleaner.hasWords(text)) continue
                    val sentences = splitSentences(text).flatMap { splitLong(it) }.filter { TextCleaner.hasWords(it) }
                    sentences.forEachIndexed { i, s ->
                        out += Segment(s, isCode = false, endsParagraph = i == sentences.lastIndex)
                    }
                }
            }
        }
        return out
    }

    private fun splitSentences(text: String): List<String> {
        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(text)
        val result = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotEmpty()) result += sentence
            start = end
            end = iterator.next()
        }
        return result
    }

    /** Very long sentences are split at commas or spaces so the voice stays natural. */
    private fun splitLong(sentence: String): List<String> {
        if (sentence.length <= MAX_LEN) return listOf(sentence)
        val parts = ArrayList<String>()
        var rest = sentence
        while (rest.length > MAX_LEN) {
            val window = rest.substring(0, MAX_LEN)
            var cut = maxOf(
                window.lastIndexOf(", "), window.lastIndexOf("; "),
                window.lastIndexOf(": "), window.lastIndexOf(" - "),
            )
            if (cut < MAX_LEN / 3) cut = window.lastIndexOf(' ')
            if (cut <= 0) cut = MAX_LEN - 1
            parts += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }
}
