package com.textreader.app.service

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.textreader.app.R
import com.textreader.app.Settings
import com.textreader.app.player.Reader
import com.textreader.app.player.ReaderState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The floating bubble and the floating panel.
 *
 * - Bubble: round button at the screen edge, muted-green ring while speaking, clipboard badge
 *   after you copy. Tap it to open the panel. Drag it onto the X at the bottom to close.
 * - Panel: Read / Play-Pause / Stop, seek bar, minimize (–) and close (X). It turns back into the
 *   bubble 5 seconds after your last touch while reading.
 * - Closing (X or drag-to-close) stops reading.
 */
@SuppressLint("ClickableViewAccessibility", "InflateParams")
class FloatingUi(
    private val service: AccessibilityService,
    private val reader: Reader,
    private val settings: Settings,
    private val onRead: () -> Unit,
    private val onPaste: () -> Unit,
) {
    private enum class Mode { HIDDEN, BUBBLE, PANEL }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val inflater = LayoutInflater.from(ContextThemeWrapper(service, R.style.Theme_TextReader))
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    // ---- Panel views ----
    private val panel = inflater.inflate(R.layout.overlay_panel, null) as TouchAwareLayout
    private val title: TextView = panel.findViewById(R.id.panel_title)
    private val preview: TextView = panel.findViewById(R.id.panel_preview)
    private val pasteBar: View = panel.findViewById(R.id.panel_paste_bar)
    private val pasteBig: View = panel.findViewById(R.id.panel_paste_big)
    private val controls: View = panel.findViewById(R.id.panel_controls)
    private val readButton: View = panel.findViewById(R.id.panel_read)
    private val playButton: View = panel.findViewById(R.id.panel_play)
    private val playIcon: ImageView = panel.findViewById(R.id.panel_play_icon)
    private val playLabel: TextView = panel.findViewById(R.id.panel_play_label)
    private val stopButton: View = panel.findViewById(R.id.panel_stop)
    private val seekBar: SeekBar = panel.findViewById(R.id.panel_seek)
    private val status: TextView = panel.findViewById(R.id.panel_status)
    private val minimizeButton: View = panel.findViewById(R.id.panel_minimize)
    private val closeButton: View = panel.findViewById(R.id.panel_close)
    private val dragHandle: View = panel.findViewById(R.id.panel_drag)

    // ---- Bubble and close-target views ----
    private val bubble: View = inflater.inflate(R.layout.overlay_bubble, null)
    private val bubbleCircle: ImageView = bubble.findViewById(R.id.bubble_circle)
    private val bubbleBadge: View = bubble.findViewById(R.id.bubble_badge)
    private val closeTarget: View = inflater.inflate(R.layout.overlay_close_target, null)

    private val panelParams = overlayParams(dp(PANEL_WIDTH_DP), WindowManager.LayoutParams.WRAP_CONTENT, touchable = true)
    private val bubbleParams = overlayParams(dp(BUBBLE_SIZE_DP), dp(BUBBLE_SIZE_DP), touchable = true)
    private val closeParams = overlayParams(dp(72), dp(72), touchable = false).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(CLOSE_MARGIN_DP)
    }

    private var mode = Mode.HIDDEN
    private var panelAttached = false
    private var bubbleAttached = false
    private var closeAttached = false

    /** You copied text and haven't pasted it yet. */
    private var pastePending = false
    /** The panel shows only the big Paste button (opened from the bubble that popped up after a copy). */
    private var pasteOnly = false
    /** The bubble popped up because of a copy and hides after 5 seconds if untouched. */
    private var temporaryBubble = false

    private var userSeeking = false
    private var wasPlaying = false
    private var lastState = ReaderState()

    private val collapseRunnable = Runnable {
        if (mode == Mode.PANEL && lastState.playing && !userSeeking) showBubble()
    }
    private val temporaryHideRunnable = Runnable {
        if (mode == Mode.BUBBLE && temporaryBubble) {
            temporaryBubble = false
            pastePending = false
            pasteOnly = false
            hideAll()
        }
    }

    init {
        panel.onAnyTouch = { scheduleCollapse() }
        readButton.setOnClickListener { onRead() }
        playButton.setOnClickListener { reader.togglePlay() }
        stopButton.setOnClickListener { reader.stop() }
        pasteBar.setOnClickListener { onPaste() }
        pasteBig.setOnClickListener { onPaste() }
        minimizeButton.setOnClickListener { showBubble() }
        closeButton.setOnClickListener { close() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) status.text = "Go to sentence ${progress + 1} of ${lastState.total}"
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                userSeeking = false
                reader.seekTo(bar.progress)
                scheduleCollapse()
            }
        })

        setUpPanelDragging()
        setUpBubbleTouch()
        scope.launch { reader.state.collect { render(it) } }
    }

    // =====================================================================
    // Public actions
    // =====================================================================

    /** Opens the full panel (from the shortcut, the app, or a bubble tap). */
    fun showPanel() {
        cancelTemporaryHide()
        val fromBubble = mode == Mode.BUBBLE
        removeBubble()
        if (!panelAttached) {
            if (fromBubble) placePanelNearBubble() else placePanelFromSettings()
            if (addWindow(panel, panelParams)) {
                panelAttached = true
                panel.post { clampPanel() }
            }
        }
        mode = Mode.PANEL
        render(lastState)
        scheduleCollapse()
    }

    /** Shows the round bubble (minimize, auto-collapse, or when the service starts). */
    fun showBubble() {
        handler.removeCallbacks(collapseRunnable)
        removePanel()
        if (!bubbleAttached) {
            placeBubbleFromSettings()
            if (addWindow(bubble, bubbleParams)) bubbleAttached = true
        }
        mode = Mode.BUBBLE
        render(lastState)
    }

    /** Close: stop reading and hide everything. Side key + Volume up brings it back. */
    fun close() {
        reader.stop()
        pastePending = false
        pasteOnly = false
        temporaryBubble = false
        hideAll()
    }

    /** You copied some text somewhere. */
    fun onCopyDetected() {
        if (!settings.pasteBubble.value) return
        pastePending = true
        if (mode == Mode.HIDDEN) {
            // Pop up for 5 seconds; tapping it opens a panel with just the Paste button.
            pasteOnly = true
            temporaryBubble = true
            showBubble()
            handler.removeCallbacks(temporaryHideRunnable)
            handler.postDelayed(temporaryHideRunnable, TEMPORARY_BUBBLE_MS)
        } else {
            // Already showing: add the Paste button; no automatic hiding.
            render(lastState)
        }
    }

    /** The copied text was loaded; show the normal panel, waiting for Play. */
    fun onPasted() {
        pastePending = false
        pasteOnly = false
        temporaryBubble = false
        cancelTemporaryHide()
        if (mode == Mode.PANEL) render(lastState) else showPanel()
    }

    /** Used while taking a screenshot so the bubble and panel are not in the picture. */
    fun setTemporarilyHidden(hidden: Boolean) {
        val visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        panel.visibility = visibility
        bubble.visibility = visibility
    }

    fun destroy() {
        hideAll()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    private fun render(state: ReaderState) {
        lastState = state

        bubbleCircle.setBackgroundResource(if (state.playing) R.drawable.bubble_bg_speaking else R.drawable.bubble_bg)
        bubbleBadge.visibility = if (pastePending) View.VISIBLE else View.GONE

        val appName = service.getString(R.string.app_name)
        title.text = if (pasteOnly) appName else state.title.ifBlank { appName }

        val showPreview = !pasteOnly && !state.preview.isNullOrBlank()
        preview.text = state.preview.orEmpty()
        preview.visibility = if (showPreview) View.VISIBLE else View.GONE
        pasteBig.visibility = if (pasteOnly) View.VISIBLE else View.GONE
        controls.visibility = if (pasteOnly) View.GONE else View.VISIBLE
        pasteBar.visibility = if (pastePending && !pasteOnly) View.VISIBLE else View.GONE

        playIcon.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        playLabel.setText(if (state.playing) R.string.pause else R.string.play)

        seekBar.isEnabled = state.total > 1
        seekBar.max = (state.total - 1).coerceAtLeast(0)
        if (!userSeeking) {
            seekBar.progress = if (state.started) state.index else 0
            status.text = statusText(state)
        }

        // Reading just started with the panel open: collapse to the bubble after 5 seconds.
        if (state.playing && !wasPlaying && mode == Mode.PANEL) scheduleCollapse()
        wasPlaying = state.playing
    }

    private fun statusText(state: ReaderState): String {
        state.message?.let { return it }
        if (state.total == 0) {
            return if (state.gathering || state.waiting) "Getting text…" else "Tap Read to read this screen."
        }
        if (!state.started && !state.playing) {
            val sentences = if (state.total == 1) "1 sentence" else "${state.total} sentences"
            return if (state.gathering) "Ready · $sentences so far · press Play" else "Ready · $sentences · press Play"
        }
        val sb = StringBuilder("Sentence ${state.index + 1} of ${state.total}")
        if (state.gathering) {
            sb.append(" · getting more…")
        } else if (state.waiting && state.playing) {
            sb.append(" · preparing…")
        }
        return sb.toString()
    }

    // =====================================================================
    // Timers
    // =====================================================================

    private fun scheduleCollapse() {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
    }

    private fun cancelTemporaryHide() {
        handler.removeCallbacks(temporaryHideRunnable)
        temporaryBubble = false
    }

    // =====================================================================
    // Windows
    // =====================================================================

    private fun overlayParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun addWindow(view: View, params: WindowManager.LayoutParams): Boolean =
        runCatching { windowManager.addView(view, params) }.isSuccess

    private fun update(view: View, params: WindowManager.LayoutParams, attached: Boolean) {
        if (attached) runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun removePanel() {
        if (panelAttached) runCatching { windowManager.removeView(panel) }
        panelAttached = false
    }

    private fun removeBubble() {
        if (bubbleAttached) runCatching { windowManager.removeView(bubble) }
        bubbleAttached = false
    }

    private fun showCloseTarget() {
        if (closeAttached) return
        closeTarget.scaleX = 1f
        closeTarget.scaleY = 1f
        if (addWindow(closeTarget, closeParams)) closeAttached = true
    }

    private fun removeCloseTarget() {
        if (closeAttached) runCatching { windowManager.removeView(closeTarget) }
        closeAttached = false
    }

    private fun hideAll() {
        handler.removeCallbacks(collapseRunnable)
        handler.removeCallbacks(temporaryHideRunnable)
        removePanel()
        removeBubble()
        removeCloseTarget()
        mode = Mode.HIDDEN
    }

    private fun screen(): Rect = windowManager.currentWindowMetrics.bounds

    // =====================================================================
    // Positions
    // =====================================================================

    private fun placeBubbleFromSettings() {
        val s = screen()
        val size = dp(BUBBLE_SIZE_DP)
        bubbleParams.x = if (settings.bubbleOnRight) s.width() - size - dp(EDGE_MARGIN_DP) else dp(EDGE_MARGIN_DP)
        bubbleParams.y = settings.bubbleY.coerceIn(dp(40), (s.height() - size - dp(120)).coerceAtLeast(dp(40)))
    }

    private fun placePanelNearBubble() {
        val s = screen()
        val width = dp(PANEL_WIDTH_DP)
        panelParams.x = if (settings.bubbleOnRight) s.width() - width - dp(8) else dp(8)
        panelParams.y = bubbleParams.y - dp(40)
    }

    private fun placePanelFromSettings() {
        panelParams.x = settings.panelX
        panelParams.y = settings.panelY
    }

    private fun clampPanel() {
        val s = screen()
        val h = if (panel.height > 0) panel.height else dp(260)
        panelParams.x = panelParams.x.coerceIn(0, (s.width() - panelParams.width).coerceAtLeast(0))
        panelParams.y = panelParams.y.coerceIn(dp(24), (s.height() - h - dp(24)).coerceAtLeast(dp(24)))
        update(panel, panelParams, panelAttached)
    }

    // =====================================================================
    // Touch: panel dragging
    // =====================================================================

    private fun setUpPanelDragging() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        val listener = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = panelParams.x
                    startY = panelParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = startX + (event.rawX - touchX).toInt()
                    panelParams.y = startY + (event.rawY - touchY).toInt()
                    update(panel, panelParams, panelAttached)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampPanel()
                    settings.panelX = panelParams.x
                    settings.panelY = panelParams.y
                    if (abs(event.rawX - touchX) < 10 && abs(event.rawY - touchY) < 10) view.performClick()
                }
            }
            true
        }
        dragHandle.setOnTouchListener(listener)
        title.setOnTouchListener(listener)
    }

    // =====================================================================
    // Touch: bubble tap, drag, snap to edge, drag onto X to close
    // =====================================================================

    private fun setUpBubbleTouch() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        val slop = dp(8).toFloat()

        bubble.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    cancelTemporaryHide() // you're using it, so it stays
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        showCloseTarget()
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        update(bubble, bubbleParams, bubbleAttached)
                        val scale = if (isOverCloseTarget()) 1.25f else 1f
                        closeTarget.scaleX = scale
                        closeTarget.scaleY = scale
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val overTarget = isOverCloseTarget()
                        removeCloseTarget()
                        if (overTarget) close() else snapToEdge()
                    } else {
                        view.performClick()
                        showPanel()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        removeCloseTarget()
                        snapToEdge()
                    }
                }
            }
            true
        }
    }

    private fun isOverCloseTarget(): Boolean {
        val s = screen()
        val size = dp(BUBBLE_SIZE_DP)
        val bubbleCenterX = bubbleParams.x + size / 2f
        val bubbleCenterY = bubbleParams.y + size / 2f
        val targetCenterX = s.width() / 2f
        val targetCenterY = s.height() - dp(CLOSE_MARGIN_DP) - dp(72) / 2f
        return hypot(bubbleCenterX - targetCenterX, bubbleCenterY - targetCenterY) < dp(90)
    }

    private fun snapToEdge() {
        val s = screen()
        val size = dp(BUBBLE_SIZE_DP)
        val onRight = bubbleParams.x + size / 2 > s.width() / 2
        val targetX = if (onRight) s.width() - size - dp(EDGE_MARGIN_DP) else dp(EDGE_MARGIN_DP)
        bubbleParams.y = bubbleParams.y.coerceIn(dp(40), (s.height() - size - dp(120)).coerceAtLeast(dp(40)))
        settings.bubbleOnRight = onRight
        settings.bubbleY = bubbleParams.y
        ValueAnimator.ofInt(bubbleParams.x, targetX).apply {
            duration = 180
            addUpdateListener { animation ->
                bubbleParams.x = animation.animatedValue as Int
                update(bubble, bubbleParams, bubbleAttached)
            }
            start()
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val PANEL_WIDTH_DP = 340
        const val BUBBLE_SIZE_DP = 66
        const val EDGE_MARGIN_DP = 4
        const val CLOSE_MARGIN_DP = 64
        const val AUTO_COLLAPSE_MS = 5000L
        const val TEMPORARY_BUBBLE_MS = 5000L
    }
}
