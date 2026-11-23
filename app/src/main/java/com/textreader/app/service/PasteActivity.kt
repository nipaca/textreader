package com.textreader.app.service

import android.app.Activity
import android.content.ClipboardManager
import com.textreader.app.app
import com.textreader.app.docs.Paste

/**
 * An invisible screen that opens for a split second after you tap Paste.
 * Android only lets the app in front read the clipboard, so this reads it and closes right away.
 * The text stays on the phone.
 */
class PasteActivity : Activity() {

    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = runCatching {
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        }.getOrNull()

        val service = ReaderAccessibilityService.instance
        if (service != null) {
            service.handlePaste(text)
        } else if (!Paste.load(app.reader, text)) {
            app.reader.showMessage("Nothing to paste. Copy some text first.")
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
