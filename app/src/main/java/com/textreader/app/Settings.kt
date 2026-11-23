package com.textreader.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Small on-device settings store. Nothing here ever leaves the phone. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("text_reader_settings", Context.MODE_PRIVATE)

    private val _speed = MutableStateFlow(prefs.getFloat(KEY_SPEED, 1.0f))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _voiceId = MutableStateFlow(Voices.byId(prefs.getInt(KEY_VOICE, Voices.DEFAULT_ID)).id)
    val voiceId: StateFlow<Int> = _voiceId.asStateFlow()

    fun setSpeed(value: Float) {
        val rounded = ((value * 10f).roundToInt() / 10f).coerceIn(MIN_SPEED, MAX_SPEED)
        if (rounded == _speed.value) return
        prefs.edit().putFloat(KEY_SPEED, rounded).apply()
        _speed.value = rounded
    }

    fun setVoice(id: Int) {
        val voice = Voices.byId(id)
        if (voice.id == _voiceId.value) return
        prefs.edit().putInt(KEY_VOICE, voice.id).apply()
        _voiceId.value = voice.id
    }

    /** Stop reading at a row of Like / Share / Copy buttons (the end of a post or answer). */
    private val _stopAtActions = MutableStateFlow(prefs.getBoolean(KEY_STOP_AT_ACTIONS, true))
    val stopAtActions: StateFlow<Boolean> = _stopAtActions.asStateFlow()

    fun setStopAtActions(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STOP_AT_ACTIONS, enabled).apply()
        _stopAtActions.value = enabled
    }

    /** Show the bubble with a Paste button after you copy text. */
    private val _pasteBubble = MutableStateFlow(prefs.getBoolean(KEY_PASTE_BUBBLE, true))
    val pasteBubble: StateFlow<Boolean> = _pasteBubble.asStateFlow()

    fun setPasteBubble(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PASTE_BUBBLE, enabled).apply()
        _pasteBubble.value = enabled
    }

    var panelX: Int
        get() = prefs.getInt(KEY_PANEL_X, 24)
        set(value) = prefs.edit().putInt(KEY_PANEL_X, value).apply()

    var panelY: Int
        get() = prefs.getInt(KEY_PANEL_Y, 400)
        set(value) = prefs.edit().putInt(KEY_PANEL_Y, value).apply()

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, 600)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()

    var bubbleOnRight: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE_RIGHT, true)
        set(value) = prefs.edit().putBoolean(KEY_BUBBLE_RIGHT, value).apply()

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        private const val KEY_SPEED = "speed"
        private const val KEY_VOICE = "voice"
        private const val KEY_PANEL_X = "panel_x"
        private const val KEY_PANEL_Y = "panel_y"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_BUBBLE_RIGHT = "bubble_right"
        private const val KEY_STOP_AT_ACTIONS = "stop_at_actions"
        private const val KEY_PASTE_BUBBLE = "paste_bubble"
    }
}
