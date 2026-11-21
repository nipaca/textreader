package com.textreader.app.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.textreader.app.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Text Reader's screen-reading service. It does not change how the phone behaves (unlike TalkBack).
 * It only shows the floating panel and, when you tap Read, collects the text on screen.
 *
 * Side key + Volume up (Samsung accessibility shortcut) brings the floating panel back.
 */
class ReaderAccessibilityService : AccessibilityService() {

    private var panel: OverlayPanel? = null
    private val scope = MainScope()
    private var collectJob: Job? = null
    private var cleanedUp = false

    private val shortcutCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            panel?.show()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        panel = OverlayPanel(this, app.reader, app.settings, onRead = ::readScreen)
        runCatching { accessibilityButtonController.registerAccessibilityButtonCallback(shortcutCallback) }
        panel?.show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanUp()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    fun showPanel() {
        panel?.show()
    }

    private fun readScreen() {
        val p = panel ?: return
        collectJob?.cancel()
        collectJob = scope.launch {
            runCatching { ScreenCollector(this@ReaderAccessibilityService, app.reader, p).run() }
                .onFailure { if (it !is CancellationException) app.reader.showMessage("Couldn't read this screen.") }
        }
    }

    private fun cleanUp() {
        if (cleanedUp) return
        cleanedUp = true
        if (instance === this) instance = null
        runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(shortcutCallback) }
        collectJob?.cancel()
        panel?.destroy()
        panel = null
        scope.cancel()
    }

    companion object {
        @Volatile
        var instance: ReaderAccessibilityService? = null
            private set
    }
}
