package com.textreader.app.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.textreader.app.app
import com.textreader.app.docs.Paste
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Text Reader's screen-reading service. It does not change how the phone behaves (unlike TalkBack).
 * It shows the floating bubble/panel, collects the text on screen when you tap Read,
 * and notices when you copy text so it can offer Paste.
 *
 * Side key + Volume up (Samsung accessibility shortcut) brings the full panel back.
 */
class ReaderAccessibilityService : AccessibilityService() {

    private var ui: FloatingUi? = null
    private val scope = MainScope()
    private var collectJob: Job? = null
    private var cleanedUp = false
    private var lastCopyAt = 0L

    private val shortcutCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            ui?.showPanel()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ui = FloatingUi(this, app.reader, app.settings, onRead = ::readScreen, onPaste = ::startPaste)
        runCatching { accessibilityButtonController.registerAccessibilityButtonCallback(shortcutCallback) }
        ui?.showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return
        val copied = when (event.eventType) {
            // Someone tapped a button labelled "Copy" (selection menu, Claude/ChatGPT/Google, etc.).
            AccessibilityEvent.TYPE_VIEW_CLICKED -> isCopyLabel(eventLabel(event))
            // A "Copied to clipboard" toast.
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                event.className?.toString()?.contains("Toast") == true && eventText(event).contains("copied")
            // Android's own clipboard pop-up.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                pkg == "com.android.systemui" &&
                    (event.className?.toString()?.contains("clipboard", ignoreCase = true) == true ||
                        eventText(event).contains("copied"))
            else -> false
        }
        if (copied) {
            val now = SystemClock.uptimeMillis()
            if (now - lastCopyAt > 1500) {
                lastCopyAt = now
                ui?.onCopyDetected()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanUp()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    /** Opens the full panel (used by the app's "Show floating button"). */
    fun showPanel() {
        ui?.showPanel()
    }

    /** Called by [PasteActivity] with the clipboard text. */
    fun handlePaste(text: String?) {
        if (Paste.load(app.reader, text)) {
            ui?.onPasted()
        } else {
            app.reader.showMessage("Nothing to paste. Copy some text first.")
        }
    }

    private fun readScreen() {
        val floating = ui ?: return
        collectJob?.cancel()
        collectJob = scope.launch {
            runCatching {
                ScreenCollector(this@ReaderAccessibilityService, app.reader, floating, app.settings.stopAtActions.value).run()
            }.onFailure { if (it !is CancellationException) app.reader.showMessage("Couldn't read this screen.") }
        }
    }

    /** Android only lets the app in front read the clipboard, so open an invisible screen for a moment. */
    private fun startPaste() {
        val intent = Intent(this, PasteActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
        runCatching { startActivity(intent) }.onFailure { app.reader.showMessage("Couldn't open the clipboard.") }
    }

    private fun eventText(event: AccessibilityEvent): String =
        (event.text.joinToString(" ") + " " + (event.contentDescription ?: "")).lowercase().trim()

    private fun eventLabel(event: AccessibilityEvent): String {
        val text = eventText(event)
        if (text.isNotBlank()) return text
        val source = event.source ?: return ""
        return (source.contentDescription ?: source.text)?.toString()?.lowercase()?.trim().orEmpty()
    }

    private fun isCopyLabel(label: String): Boolean {
        if (label.isEmpty() || label.length > 40) return false
        return label == "copy" || label == "copied" || label.startsWith("copy ") || label.startsWith("copy,") ||
            label == "copy to clipboard"
    }

    private fun cleanUp() {
        if (cleanedUp) return
        cleanedUp = true
        if (instance === this) instance = null
        runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(shortcutCallback) }
        collectJob?.cancel()
        ui?.destroy()
        ui = null
        scope.cancel()
    }

    companion object {
        @Volatile
        var instance: ReaderAccessibilityService? = null
            private set
    }
}
