package com.textreader.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import com.textreader.app.ocr.OcrEngine
import com.textreader.app.player.Reader
import com.textreader.app.player.Source
import com.textreader.app.text.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Reads the screen: starts speaking what is visible right away, then scrolls down in the
 * background and keeps adding new text — up to [MAX_SCREENS] screens. If it stops because of
 * that limit, it scrolls once more so the next "Read" starts at screen 11.
 */
class ScreenCollector(
    private val service: AccessibilityService,
    private val reader: Reader,
    private val ui: FloatingUi,
    private val stopAtActions: Boolean,
) {

    private class ResumePoint(val packageName: String, val tail: List<String>)

    companion object {
        const val MAX_SCREENS = 10
        private const val SETTLE_MS = 450L
        private var resumePoint: ResumePoint? = null

        // Apps where posts or answers are short, so a Like/Share/Copy row ends reading right away.
        private val postApps = setOf(
            "com.facebook.katana", "com.facebook.lite", "com.twitter.android", "com.reddit.frontpage",
            "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.googlequicksearchbox",
            "com.google.android.apps.bard",
        )

        /** Elsewhere (web pages), a share row near the top of an article shouldn't end reading early. */
        private const val MIN_WORDS_ELSEWHERE = 40
    }

    suspend fun run() {
        val root = withContext(Dispatchers.Default) { findTargetRoot() }
        if (root == null) {
            reader.showMessage("Can't see this screen. Try again.")
            return
        }
        val pkg = root.packageName?.toString().orEmpty()
        val token = reader.startNew(screenTitle(pkg), Source.SCREEN)
        val first = withContext(Dispatchers.Default) { ScreenExtractor.extract(root, stopAtActions) }

        if (first.chunks.none { !it.isAction }) {
            // No readable text in the accessibility tree (text drawn as a picture). Try OCR.
            val blocks = ocrScreen(root)
            if (blocks.isNotEmpty()) reader.append(token, blocks)
            reader.finish(token, "No text found on this screen.")
            return
        }

        val assembler = BlockAssembler { blocks -> reader.append(token, blocks) }
        val collected = ArrayList<String>()
        val minWords = if (pkg in postApps) 1 else MIN_WORDS_ELSEWHERE
        var words = 0
        var stoppedAtActions = false

        /** Keeps the text up to a Like/Share/Copy row (once enough has been read), then stops. */
        fun upToActionRow(chunks: List<Chunk>): List<Chunk> {
            val keep = ArrayList<Chunk>()
            for (chunk in chunks) {
                if (chunk.isAction) {
                    if (words >= minWords) {
                        stoppedAtActions = true
                        return keep
                    }
                    continue
                }
                keep += chunk
                words += wordCount(chunk.text)
            }
            return keep
        }

        // A button row near the top belongs to the post above: skip it and anything before it.
        var firstChunks = first.chunks
        val topBand = first.window.top + first.window.height() / 5
        val leadingRow = firstChunks.indexOfFirst { it.isAction }
        if (leadingRow >= 0 && firstChunks[leadingRow].top < topBand) firstChunks = firstChunks.drop(leadingRow + 1)

        // Continuing after a 10-screen stop? Skip what was already read.
        val resume = resumePoint?.takeIf { it.packageName == pkg }
        resumePoint = null
        val firstFresh = if (resume != null) Overlap.fresh(resume.tail, firstChunks) else firstChunks
        if (resume != null) collected.addAll(resume.tail)

        assembler.add(upToActionRow(firstFresh))
        collected.addAll(firstFresh.map { it.text })
        var lastScreen = first.texts
        var screens = 1
        var emptyInARow = 0
        var hitLimit = false

        while (!stoppedAtActions && reader.isCurrent(token)) {
            if (screens >= MAX_SCREENS) {
                hitLimit = true
                break
            }
            val moved = withContext(Dispatchers.Default) { scrollForward() }
            if (!moved) break
            val next = waitForChange(pkg, lastScreen) ?: break
            screens++
            val fresh = Overlap.fresh(collected.takeLast(120), next.chunks, lastScreen)
            lastScreen = next.texts
            if (fresh.none { !it.isAction }) {
                emptyInARow++
                if (emptyInARow >= 2) break
                if (fresh.isEmpty()) continue
            } else {
                emptyInARow = 0
            }
            assembler.add(upToActionRow(fresh))
            collected.addAll(fresh.map { it.text })
        }

        assembler.flush()

        if (hitLimit && !stoppedAtActions && reader.isCurrent(token)) {
            val moved = withContext(Dispatchers.Default) { scrollForward() }
            if (moved) {
                delay(SETTLE_MS)
                resumePoint = ResumePoint(pkg, collected.takeLast(120))
            }
        }
        reader.finish(token)
    }

    private fun wordCount(text: String): Int =
        text.split(Regex("\\s+")).count { word -> word.any { it.isLetter() } }

    /** After scrolling, waits for the screen to update. Null if the app changed or nothing moved. */
    private suspend fun waitForChange(pkg: String, previous: List<String>): Screen? {
        repeat(4) {
            delay(SETTLE_MS)
            val screen = withContext(Dispatchers.Default) {
                val root = findTargetRoot() ?: return@withContext null
                if (root.packageName?.toString() != pkg) return@withContext Screen("", emptyList())
                ScreenExtractor.extract(root, stopAtActions)
            } ?: return null
            if (screen.packageName != pkg) return null // you switched apps: stop collecting
            if (screen.texts != previous) return screen
        }
        return null
    }

    private fun findTargetRoot(): AccessibilityNodeInfo? {
        val own = service.packageName
        service.rootInActiveWindow?.let { if (it.packageName?.toString() != own) return it }
        return service.windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() != own }
            .maxByOrNull { area(ScreenExtractor.bounds(it)) }
    }

    private fun scrollForward(): Boolean {
        val root = findTargetRoot() ?: return false
        val target = findScrollable(root) ?: return false
        val down = AccessibilityAction.ACTION_SCROLL_DOWN.id
        val forward = AccessibilityAction.ACTION_SCROLL_FORWARD.id
        val actions = target.actionList.map { it.id }
        return when {
            down in actions -> target.performAction(down)
            forward in actions -> target.performAction(forward)
            else -> false
        }
    }

    /** The biggest vertically-scrollable area on screen (the page or the list). */
    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val webOnly = ScreenExtractor.isBrowser(root.packageName?.toString().orEmpty())
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4000) {
            val node = queue.removeFirst()
            visited++
            if (!node.isVisibleToUser) continue
            if (node.isScrollable && !isHorizontal(node)) {
                val ids = node.actionList.map { it.id }
                val canGoDown = AccessibilityAction.ACTION_SCROLL_DOWN.id in ids ||
                    AccessibilityAction.ACTION_SCROLL_FORWARD.id in ids
                val cls = node.className?.toString().orEmpty()
                val allowed = !webOnly || cls == "android.webkit.WebView" || best == null
                if (canGoDown && allowed) {
                    val a = area(ScreenExtractor.bounds(node))
                    if (a > bestArea) {
                        best = node
                        bestArea = a
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    private fun isHorizontal(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("ViewPager") || cls.contains("HorizontalScrollView")) return true
        val info = node.collectionInfo ?: return false
        return info.rowCount <= 1 && info.columnCount > 1
    }

    private fun area(r: Rect): Long = r.width().toLong() * r.height().toLong()

    private fun screenTitle(pkg: String): String {
        val windowTitle = runCatching {
            service.windows.firstOrNull { it.root?.packageName?.toString() == pkg }?.title?.toString()
        }.getOrNull()
        if (!windowTitle.isNullOrBlank()) return windowTitle
        val label = runCatching {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        return label?.takeIf { it.isNotBlank() } ?: "This screen"
    }

    // ---------------- OCR fallback ----------------

    private suspend fun ocrScreen(root: AccessibilityNodeInfo): List<Block> {
        reader.showMessage("Reading text from the screen image…")
        ui.setTemporarilyHidden(true)
        val shot = try {
            delay(250)
            takeScreenshot()
        } finally {
            ui.setTemporarilyHidden(false)
        }
        shot ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val r = ScreenExtractor.bounds(root)
            val left = r.left.coerceIn(0, shot.width - 1)
            val top = r.top.coerceIn(0, shot.height - 1)
            val width = (r.right.coerceAtMost(shot.width) - left).coerceAtLeast(1)
            val height = (r.bottom.coerceAtMost(shot.height) - top).coerceAtLeast(1)
            val cropped = Bitmap.createBitmap(shot, left, top, width, height)
            runCatching { OcrEngine.toBlocks(OcrEngine.recognize(service, cropped)) }.getOrDefault(emptyList())
        }
    }

    private suspend fun takeScreenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    buffer.close()
                    if (cont.isActive) cont.resume(software)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }
}

/** Joins on-screen pieces into paragraphs, keeping the last paragraph open until the next screen. */
class BlockAssembler(private val emit: (List<Block>) -> Unit) {
    private var pending: StringBuilder? = null

    fun add(chunks: List<Chunk>) {
        val out = ArrayList<Block>()
        for (chunk in chunks) {
            if (chunk.isCode) {
                flushInto(out)
                out += Block.Code
                continue
            }
            val open = pending
            if (chunk.joinPrev && open != null) {
                open.append(' ').append(chunk.text)
            } else {
                flushInto(out)
                pending = StringBuilder(chunk.text)
            }
        }
        if (out.isNotEmpty()) emit(out)
    }

    fun flush() {
        val out = ArrayList<Block>()
        flushInto(out)
        if (out.isNotEmpty()) emit(out)
    }

    private fun flushInto(out: MutableList<Block>) {
        pending?.let { if (it.isNotBlank()) out += Block.Text(it.toString()) }
        pending = null
    }
}
