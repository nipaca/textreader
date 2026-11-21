package com.textreader.app.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import com.textreader.app.R
import com.textreader.app.Settings
import com.textreader.app.player.Reader
import com.textreader.app.player.ReaderState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The floating Read / Play-Pause / seek bar panel shown over other apps. */
class OverlayPanel(
    private val service: AccessibilityService,
    private val reader: Reader,
    private val settings: Settings,
    private val onRead: () -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val themed = ContextThemeWrapper(service, R.style.Theme_TextReader)

    @SuppressLint("InflateParams")
    private val root: View = LayoutInflater.from(themed).inflate(R.layout.overlay_panel, null)
    private val title: TextView = root.findViewById(R.id.panel_title)
    private val status: TextView = root.findViewById(R.id.panel_status)
    private val readButton: Button = root.findViewById(R.id.panel_read)
    private val playButton: ImageButton = root.findViewById(R.id.panel_play)
    private val closeButton: ImageButton = root.findViewById(R.id.panel_close)
    private val seekBar: SeekBar = root.findViewById(R.id.panel_seek)
    private val dragHandle: View = root.findViewById(R.id.panel_drag)

    private val scope = MainScope()
    private var stateJob: Job? = null
    private var attached = false
    private var userSeeking = false
    private var lastState = ReaderState()

    private val params = WindowManager.LayoutParams(
        (PANEL_WIDTH_DP * service.resources.displayMetrics.density).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = settings.panelX
        y = settings.panelY
    }

    init {
        readButton.setOnClickListener { onRead() }
        playButton.setOnClickListener { reader.togglePlay() }
        closeButton.setOnClickListener { hide() }

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
            }
        })

        setUpDragging()
    }

    val isShowing: Boolean get() = attached

    fun show() {
        if (attached) {
            root.visibility = View.VISIBLE
            return
        }
        clampToScreen()
        windowManager.addView(root, params)
        attached = true
        stateJob = scope.launch { reader.state.collect { render(it) } }
    }

    fun hide() {
        if (!attached) return
        stateJob?.cancel()
        stateJob = null
        runCatching { windowManager.removeView(root) }
        attached = false
    }

    /** Used while taking a screenshot so the panel itself is not in the picture. */
    fun setTemporarilyHidden(hidden: Boolean) {
        if (attached) root.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun destroy() {
        hide()
        scope.cancel()
    }

    private fun render(state: ReaderState) {
        lastState = state
        title.text = state.title.ifBlank { service.getString(R.string.app_name) }

        playButton.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription = if (state.playing) "Pause" else "Play"
        playButton.isEnabled = state.total > 0 || state.gathering

        seekBar.isEnabled = state.total > 1
        seekBar.max = (state.total - 1).coerceAtLeast(0)
        if (!userSeeking) seekBar.progress = state.index

        if (!userSeeking) status.text = statusText(state)
    }

    private fun statusText(state: ReaderState): String {
        state.message?.let { return it }
        if (state.total == 0) {
            return if (state.gathering || state.waiting) "Getting text…" else "Tap Read to read this screen."
        }
        val sb = StringBuilder("Sentence ${state.index + 1} of ${state.total}")
        if (state.gathering) sb.append(" · getting more…")
        else if (state.waiting && state.playing) sb.append(" · preparing…")
        return sb.toString()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpDragging() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        val listener = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    if (attached) windowManager.updateViewLayout(root, params)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampToScreen()
                    if (attached) windowManager.updateViewLayout(root, params)
                    settings.panelX = params.x
                    settings.panelY = params.y
                    if (abs(event.rawX - touchX) < 10 && abs(event.rawY - touchY) < 10) view.performClick()
                }
            }
            true
        }
        dragHandle.setOnTouchListener(listener)
        title.setOnTouchListener(listener)
    }

    private fun clampToScreen() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val w = params.width
        val h = if (root.height > 0) root.height else (140 * service.resources.displayMetrics.density).toInt()
        params.x = params.x.coerceIn(0, (bounds.width() - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (bounds.height() - h).coerceAtLeast(0))
    }

    private companion object {
        const val PANEL_WIDTH_DP = 300
    }
}
