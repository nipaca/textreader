package com.textreader.app.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Wraps the offline Kokoro neural voice (via sherpa-onnx).
 * Everything runs on the phone; no network is used or even allowed.
 */
class KokoroEngine(private val context: Context) {

    @Volatile
    private var tts: OfflineTts? = null
    private val loadLock = Mutex()

    @Volatile
    var sampleRate: Int = 24000
        private set

    val isLoaded: Boolean get() = tts != null

    suspend fun ensureLoaded() {
        if (tts != null) return
        loadLock.withLock {
            if (tts != null) return
            val engine = withContext(Dispatchers.IO) { load() }
            sampleRate = engine.sampleRate()
            tts = engine
        }
    }

    /** Turns one sentence into audio samples. Call from one thread at a time. */
    fun generate(text: String, voiceId: Int, speed: Float): FloatArray {
        val engine = tts ?: error("Voice not loaded")
        return engine.generate(text, voiceId, speed).samples
    }

    private fun load(): OfflineTts {
        val dir = copyModelFiles()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, "model.onnx").absolutePath,
                    voices = File(dir, "voices.bin").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath,
                    lexicon = File(dir, "lexicon-us-en.txt").absolutePath,
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        return OfflineTts(assetManager = null, config = config)
    }

    /**
     * The voice engine reads its files from normal storage, so copy them out of the APK
     * once after each install or update (takes a few seconds the first time).
     */
    private fun copyModelFiles(): File {
        val target = File(context.filesDir, ASSET_DIR)
        val marker = File(context.filesDir, "$ASSET_DIR.stamp")
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (target.isDirectory && marker.isFile && marker.readText() == stamp) return target
        marker.delete()
        target.deleteRecursively()
        copyAssetTree(ASSET_DIR, target)
        marker.writeText(stamp)
        return target
    }

    private fun copyAssetTree(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            dest.mkdirs()
            for (child in children) copyAssetTree("$assetPath/$child", File(dest, child))
            return
        }
        try {
            context.assets.open(assetPath).use { input ->
                dest.parentFile?.mkdirs()
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: FileNotFoundException) {
            dest.mkdirs() // an empty folder
        }
    }

    companion object {
        const val ASSET_DIR = "kokoro"
    }
}
