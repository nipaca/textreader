package com.textreader.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.PowerManager
import com.textreader.app.Settings
import com.textreader.app.text.Block
import com.textreader.app.text.Segment
import com.textreader.app.text.Segmenter
import com.textreader.app.tts.KokoroEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

enum class Source { NONE, SCREEN, FILE }

data class ReaderState(
    val title: String = "",
    val source: Source = Source.NONE,
    /** Sentence being spoken (0-based). */
    val index: Int = 0,
    /** Number of sentences known so far. */
    val total: Int = 0,
    /** True while speaking (or about to). False when paused or finished. */
    val playing: Boolean = false,
    /** Preparing the voice, the next sentence, or more text. */
    val waiting: Boolean = false,
    /** More text is still being collected (auto-scroll or a big file). */
    val gathering: Boolean = false,
    val message: String? = null,
)

/**
 * The reading engine shared by the floating button and the app screen.
 * Text comes in as [Block]s (possibly in several batches), is split into sentences,
 * and is spoken with the offline Kokoro voice. Supports play/pause and seeking by sentence.
 */
class Reader(private val context: Context, private val settings: Settings) {

    private val engine = KokoroEngine(context)

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val control = Dispatchers.Default.limitedParallelism(1)

    private val generator = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-generate").apply { priority = Thread.MAX_PRIORITY - 1 }
    }.asCoroutineDispatcher()

    // ---- Content (guarded by lock) ----
    private val lock = Any()
    private val segments = ArrayList<Segment>()
    private var inputComplete = true
    private var token = 0L
    private val audioCache = HashMap<Int, Deferred<FloatArray>>()
    private val inputSignal = MutableStateFlow(0L)

    // ---- Playback ----
    private var playJob: Job? = null

    @Volatile private var paused = false
    @Volatile private var currentIndex = 0
    @Volatile private var finished = false
    @Volatile private var track: AudioTrack? = null
    private var resumeOnFocusGain = false

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
        .build()

    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TextReader:reading")
        .apply { setReferenceCounted(false) }

    init {
        // New voice or speed: drop audio prepared in advance so the next sentence uses it.
        scope.launch {
            combine(settings.speed, settings.voiceId) { s, v -> s to v }
                .drop(1)
                .collect { clearAudioCache() }
        }
        // Load the voice in the background so the first "Read" starts quickly.
        scope.launch { runCatching { engine.ensureLoaded() } }
    }

    // =====================================================================
    // Input
    // =====================================================================

    /** Starts a new reading. Returns a token for [append]/[finish]; older tokens are ignored. */
    fun startNew(title: String, source: Source): Long {
        val newToken: Long
        synchronized(lock) {
            token += 1
            newToken = token
            segments.clear()
            inputComplete = false
            clearAudioCacheLocked()
        }
        inputSignal.update { it + 1 }
        launchControl {
            stopPlaybackJob()
            currentIndex = 0
            paused = false
            finished = false
            publish { ReaderState(title = title, source = source, playing = true, waiting = true) }
            startPlayJob()
        }
        return newToken
    }

    fun isCurrent(t: Long): Boolean = synchronized(lock) { t == token }

    fun append(t: Long, blocks: List<Block>) {
        if (blocks.isEmpty()) return
        val newSegments = Segmenter.toSegments(blocks)
        if (newSegments.isEmpty()) return
        synchronized(lock) {
            if (t != token) return
            var list = newSegments
            if (segments.lastOrNull()?.isCode == true && list.first().isCode) list = list.drop(1)
            segments.addAll(list)
        }
        inputSignal.update { it + 1 }
        publish { it.copy(message = null) }
    }

    /** No more text is coming for this reading. */
    fun finish(t: Long, emptyMessage: String = "Nothing to read here.") {
        val empty: Boolean
        synchronized(lock) {
            if (t != token) return
            inputComplete = true
            empty = segments.isEmpty()
        }
        inputSignal.update { it + 1 }
        if (empty) publish { it.copy(message = emptyMessage, playing = false, waiting = false) } else publish()
    }

    /** Shows a short status line on the panel and app screen. */
    fun showMessage(message: String?) = publish { it.copy(message = message) }

    // =====================================================================
    // Controls
    // =====================================================================

    fun togglePlay() = launchControl {
        val total = totalNow()
        val running = playJob?.isActive == true
        when {
            total == 0 && !gatheringNow() -> Unit
            !running -> {
                if (finished) currentIndex = 0
                paused = false
                startPlayJob()
            }
            paused -> resumeInternal()
            else -> pauseInternal()
        }
    }

    fun pause() = launchControl { pauseInternal() }

    fun seekTo(index: Int) = launchControl {
        val total = totalNow()
        if (total == 0) return@launchControl
        val target = index.coerceIn(0, total - 1)
        val wasPlaying = playJob?.isActive == true && !paused
        stopPlaybackJob()
        currentIndex = target
        finished = false
        trimAudioCache(target)
        if (wasPlaying) {
            paused = false
            startPlayJob()
        } else {
            publish { it.copy(playing = false, waiting = false) }
        }
    }

    fun stop() = launchControl {
        stopPlaybackJob()
        paused = false
        publish { it.copy(playing = false, waiting = false) }
    }

    // =====================================================================
    // Playback internals
    // =====================================================================

    private fun launchControl(block: suspend () -> Unit) {
        scope.launch(control) { block() }
    }

    private fun startPlayJob() {
        finished = false
        playJob = scope.launch { playLoop() }
    }

    private suspend fun stopPlaybackJob() {
        playJob?.cancelAndJoin()
        playJob = null
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }
        setAwake(false)
    }

    private fun pauseInternal() {
        if (playJob?.isActive != true || paused) return
        paused = true
        runCatching { track?.pause() }
        setAwake(false)
        publish { it.copy(playing = false) }
    }

    private fun resumeInternal() {
        if (!paused) return
        if (!requestFocus()) {
            publish { it.copy(message = "Another app is using audio.") }
            return
        }
        paused = false
        runCatching { track?.play() }
        setAwake(true)
        publish { it.copy(playing = true, message = null) }
    }

    private suspend fun playLoop() {
        try {
            if (!engine.isLoaded) publish { it.copy(waiting = true, message = "Loading voice (first time takes a bit longer)…") }
            engine.ensureLoaded()
            publish { it.copy(message = null) }

            if (!requestFocus()) {
                paused = false
                publish { it.copy(playing = false, waiting = false, message = "Another app is using audio.") }
                return
            }
            val audio = ensureTrack(engine.sampleRate)
            audio.play()
            setAwake(true)
            publish { it.copy(playing = true) }

            while (true) {
                val i = currentIndex
                waitForSegment(i) ?: break
                prefetch(i + 1)
                prefetch(i + 2)
                publish { it.copy(waiting = !isAudioReady(i)) }
                val pcm = obtainAudio(i)
                publish { it.copy(waiting = false) }
                writePcm(audio, pcm)
                synchronized(lock) { audioCache.remove(i) }
                currentIndex = i + 1
                publish()
            }

            drain(audio)
            currentIndex = (totalNow() - 1).coerceAtLeast(0)
            finished = true
            publish { it.copy(playing = false, waiting = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            publish { it.copy(playing = false, waiting = false, message = "Voice problem: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            setAwake(false)
        }
    }

    /** Waits until sentence [i] exists. Returns null when the text has ended. */
    private suspend fun waitForSegment(i: Int): Segment? {
        while (true) {
            val version = inputSignal.value
            synchronized(lock) {
                if (i < segments.size) return segments[i]
                if (inputComplete) return null
            }
            publish { it.copy(waiting = true) }
            inputSignal.first { it != version }
        }
    }

    private suspend fun obtainAudio(i: Int): FloatArray {
        while (true) {
            val job = audioFor(i) ?: return FloatArray(0)
            try {
                return job.await()
            } catch (e: CancellationException) {
                // Our own job was cancelled -> stop. Otherwise the prepared audio was dropped
                // (voice/speed changed), so make it again.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private fun isAudioReady(i: Int): Boolean = synchronized(lock) { audioCache[i]?.isCompleted == true }

    private fun prefetch(i: Int) {
        audioFor(i)
    }

    private fun audioFor(i: Int): Deferred<FloatArray>? {
        synchronized(lock) {
            if (i < 0 || i >= segments.size) return null
            val existing = audioCache[i]
            if (existing != null && !existing.isCancelled) return existing
            val segment = segments[i]
            val voice = settings.voiceId.value
            val speed = settings.speed.value
            val job = scope.async(generator) { synthesize(segment, voice, speed) }
            audioCache[i] = job
            return job
        }
    }

    private fun synthesize(segment: Segment, voice: Int, speed: Float): FloatArray = try {
        val pcm = engine.generate(segment.text, voice, speed)
        if (segment.endsParagraph) pcm + FloatArray(engine.sampleRate / 4) else pcm
    } catch (e: Throwable) {
        FloatArray(0)
    }

    private fun clearAudioCache() = synchronized(lock) { clearAudioCacheLocked() }

    private fun clearAudioCacheLocked() {
        audioCache.values.forEach { it.cancel() }
        audioCache.clear()
    }

    private fun trimAudioCache(keepFrom: Int) = synchronized(lock) {
        val iterator = audioCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key < keepFrom || entry.key > keepFrom + 2) {
                entry.value.cancel()
                iterator.remove()
            }
        }
    }

    private suspend fun writePcm(audio: AudioTrack, pcm: FloatArray) {
        var offset = 0
        while (offset < pcm.size) {
            currentCoroutineContext().ensureActive()
            if (paused) {
                delay(50)
                continue
            }
            val written = audio.write(pcm, offset, minOf(4096, pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
            when {
                written < 0 -> return
                written == 0 -> delay(15)
                else -> offset += written
            }
        }
    }

    /** Lets the last bit of buffered audio finish playing. */
    private suspend fun drain(audio: AudioTrack) {
        val bufferMs = audio.bufferSizeInFrames * 1000L / audio.sampleRate.coerceAtLeast(1)
        var waited = 0L
        while (waited < bufferMs + 250) {
            delay(50)
            if (!paused) waited += 50
        }
    }

    private fun ensureTrack(rate: Int): AudioTrack {
        track?.let { if (it.sampleRate == rate) return it else it.release() }
        val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val created = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, rate)) // about a quarter second
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = created
        return created
    }

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun onFocusChange(change: Int) = launchControl {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pauseInternal()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val wasPlaying = playJob?.isActive == true && !paused
                pauseInternal()
                resumeOnFocusGain = wasPlaying
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) resumeInternal()
                resumeOnFocusGain = false
            }
        }
    }

    private fun setAwake(awake: Boolean) {
        if (awake) {
            if (!wakeLock.isHeld) wakeLock.acquire(4 * 60 * 60 * 1000L)
        } else if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun totalNow(): Int = synchronized(lock) { segments.size }
    private fun gatheringNow(): Boolean = synchronized(lock) { !inputComplete }

    private fun publish(transform: (ReaderState) -> ReaderState = { it }) {
        val total: Int
        val gathering: Boolean
        synchronized(lock) {
            total = segments.size
            gathering = !inputComplete
        }
        val index = currentIndex.coerceIn(0, (total - 1).coerceAtLeast(0))
        _state.update { transform(it).copy(total = total, gathering = gathering, index = index) }
    }
}
