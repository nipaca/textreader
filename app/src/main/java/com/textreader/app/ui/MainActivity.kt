package com.textreader.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.textreader.app.Settings
import com.textreader.app.Voices
import com.textreader.app.app
import com.textreader.app.docs.DocumentLoader
import com.textreader.app.player.Reader
import com.textreader.app.player.ReaderState
import com.textreader.app.player.Source
import com.textreader.app.service.ReaderAccessibilityService
import com.textreader.app.text.Block
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) DocumentLoader.open(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val reader = app.reader
        val settings = app.settings
        setContent {
            TextReaderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        reader = reader,
                        settings = settings,
                        onOpenFile = { openFile.launch(DocumentLoader.MIME_TYPES) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TextReaderTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun MainScreen(reader: Reader, settings: Settings, onOpenFile: () -> Unit) {
    val context = LocalContext.current
    val state by reader.state.collectAsStateWithLifecycle()
    var serviceOn by remember { mutableStateOf(isServiceEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { serviceOn = isServiceEnabled(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Text Reader", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        NowReadingCard(state, reader)

        SectionCard(title = "Read a file") {
            Text("PDF, TXT, MD, DOCX or EPUB. Scanned PDF pages are read with on-phone text recognition.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenFile) { Text("Open a file") }
        }

        ScreenReadingCard(serviceOn)

        VoiceSettingsCard(settings, reader)

        Text(
            "Private by design: this app has no internet permission. Your text never leaves the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NowReadingCard(state: ReaderState, reader: Reader) {
    SectionCard(title = if (state.title.isBlank()) "Nothing playing" else state.title) {
        Text(statusLine(state), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.total > 0 || state.gathering) {
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val maxIndex = (state.total - 1).coerceAtLeast(1)
            Spacer(Modifier.height(8.dp))
            Slider(
                value = if (dragging) dragValue else state.index.toFloat().coerceAtMost(maxIndex.toFloat()),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    reader.seekTo(dragValue.roundToInt())
                    dragging = false
                },
                valueRange = 0f..maxIndex.toFloat(),
                enabled = state.total > 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { reader.togglePlay() }) { Text(if (state.playing) "Pause" else "Play") }
                Spacer(Modifier.width(12.dp))
                if (dragging) Text("Go to sentence ${dragValue.roundToInt() + 1}")
            }
        }
    }
}

@Composable
private fun ScreenReadingCard(serviceOn: Boolean) {
    val context = LocalContext.current
    SectionCard(title = "Read what's on screen") {
        if (serviceOn) {
            Text("Screen reading is on. Press Side key + Volume up to bring back the floating button.")
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = { ReaderAccessibilityService.instance?.showPanel() }) { Text("Show floating button") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { openAccessibilitySettings(context) }) { Text("Settings") }
            }
        } else {
            Text("Screen reading is off. To turn it on:")
            Spacer(Modifier.height(8.dp))
            Text(
                "1. Tap the button below, then Installed apps → Text Reader → turn it on.\n" +
                    "2. If Android says it's a restricted setting: open Settings → Apps → Text Reader → ⋮ (top right) → " +
                    "Allow restricted settings, then try step 1 again.\n" +
                    "3. Shortcut: Settings → Accessibility → Advanced settings → Side and Volume up buttons → choose Text Reader.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { openAccessibilitySettings(context) }) { Text("Open accessibility settings") }
        }
    }
}

@Composable
private fun VoiceSettingsCard(settings: Settings, reader: Reader) {
    val speed by settings.speed.collectAsStateWithLifecycle()
    val voiceId by settings.voiceId.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val voice = Voices.byId(voiceId)

    SectionCard(title = "Voice and speed") {
        Text("Speed: ${"%.1f".format(speed)}×")
        Slider(
            value = speed,
            onValueChange = { settings.setSpeed(it) },
            valueRange = Settings.MIN_SPEED..Settings.MAX_SPEED,
            steps = 14,
        )
        Spacer(Modifier.height(8.dp))
        Text("Voice")
        Box {
            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(voice.label) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Voices.all.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v.label) },
                        onClick = {
                            settings.setVoice(v.id)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = {
            val token = reader.startNew("Voice preview", Source.FILE)
            reader.append(token, listOf(Block.Text("Hi, I'm ${voice.name}. This is how I sound when I read to you.")))
            reader.finish(token)
        }) { Text("Preview voice") }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private fun statusLine(state: ReaderState): String {
    state.message?.let { return it }
    if (state.total == 0) {
        return if (state.gathering || state.waiting) "Getting text…" else "Open a file, or use the floating button to read the screen."
    }
    val base = "Sentence ${state.index + 1} of ${state.total}"
    return when {
        state.gathering -> "$base · getting more…"
        state.waiting && state.playing -> "$base · preparing…"
        else -> base
    }
}

private fun isServiceEnabled(context: Context): Boolean {
    if (ReaderAccessibilityService.instance != null) return true
    val enabled = AndroidSettings.Secure.getString(
        context.contentResolver,
        AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.endsWith("ReaderAccessibilityService", ignoreCase = true) && it.startsWith(context.packageName) }
}

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
