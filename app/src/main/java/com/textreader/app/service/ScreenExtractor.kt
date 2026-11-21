package com.textreader.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** One piece of text found on screen, in reading order. */
data class Chunk(val text: String, val isCode: Boolean, val joinPrev: Boolean)

data class Screen(val packageName: String, val chunks: List<Chunk>) {
    val texts: List<String> get() = chunks.map { it.text }
}

/**
 * Pulls readable text out of another app's window (via the accessibility tree).
 * Skips images, buttons, menus, toolbars, ads and similar clutter.
 * On web pages it reads only the main content when the page marks it.
 */
object ScreenExtractor {

    private const val WEBVIEW = "android.webkit.WebView"
    private const val CHROME_ROLE = "AccessibilityNodeInfo.chromeRole"
    private const val MAX_DEPTH = 120

    private val browsers = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus",
        "com.microsoft.emmx", "com.brave.browser", "com.duckduckgo.mobile.android",
        "com.opera.browser", "com.opera.mini.native", "com.vivaldi.browser",
        "com.kiwibrowser.browser", "org.chromium.chrome",
    )

    // Web page parts that are not the main text.
    private val skipRoles = setOf(
        "navigation", "banner", "contentinfo", "complementary", "search", "form",
        "dialog", "alertdialog", "menu", "menubar", "menuitem", "menuitemcheckbox", "menuitemradio",
        "menulistpopup", "menulistoption", "toolbar", "tablist", "tab", "button", "popupbutton",
        "togglebutton", "checkbox", "radiobutton", "switch", "combobox", "comboboxgrouping",
        "comboboxmenubutton", "comboboxselect", "listbox", "listboxoption", "textfield",
        "textfieldwithcombobox", "searchbox", "spinbutton", "slider", "scrollbar", "image", "img",
        "imagemap", "svgroot", "graphicsobject", "graphicssymbol", "canvas", "video", "audio",
        "iframe", "iframepresentational", "embeddedobject", "pluginobject", "marquee", "timer",
        "progressindicator", "meter", "tooltip", "status",
    )

    private val adLabels = setOf(
        "ad", "ads", "advertisement", "advertisements", "sponsored", "promoted",
        "sponsored content", "adchoices", "ad choices", "skip ad",
    )

    private class Ctx(val inWeb: Boolean, val window: Rect)
    private class Raw(val text: String, val isCode: Boolean, val rect: Rect)

    fun isBrowser(packageName: String) = packageName in browsers

    fun extract(root: AccessibilityNodeInfo): Screen {
        val pkg = root.packageName?.toString().orEmpty()
        val window = bounds(root)
        val out = ArrayList<Raw>()

        if (isBrowser(pkg)) {
            // Only the web page itself, not the browser's address bar, tabs or menus.
            val webRoots = ArrayList<AccessibilityNodeInfo>()
            findWebRoots(root, webRoots, 0)
            if (webRoots.isEmpty()) {
                walk(root, out, Ctx(inWeb = true, window = window), 0, isStart = true, parentClickable = false)
            } else {
                for (web in webRoots) {
                    val main = findRole(web, "main", 0)
                    walk(main ?: web, out, Ctx(inWeb = true, window = window), 0, isStart = true, parentClickable = false)
                }
            }
        } else {
            walk(root, out, Ctx(inWeb = false, window = window), 0, isStart = true, parentClickable = false)
        }

        val chunks = out.mapIndexed { i, raw ->
            val join = i > 0 && !raw.isCode && !out[i - 1].isCode && sameLine(out[i - 1].rect, raw.rect)
            Chunk(raw.text.trim(), raw.isCode, join)
        }.filter { it.isCode || it.text.isNotEmpty() }
        return Screen(pkg, chunks)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<Raw>,
        ctx: Ctx,
        depth: Int,
        isStart: Boolean,
        parentClickable: Boolean,
    ) {
        if (depth > MAX_DEPTH) return
        if (!node.isVisibleToUser) return
        val cls = node.className?.toString().orEmpty()
        val role = role(node)

        if (!isStart) {
            if (ctx.inWeb && cls == WEBVIEW) return // an embedded frame, usually an ad
            if (role != null && role in skipRoles) return
            if (role == "pre") {
                out += Raw("", isCode = true, rect = bounds(node))
                return
            }
            if (role == "code") {
                val code = subtreeText(node, 0)
                if (code.contains('\n') || code.length > 60) {
                    out += Raw("", isCode = true, rect = bounds(node))
                    return
                }
            }
            if (isImage(cls)) return
            if (isControl(cls)) return
        }

        val clickable = node.isClickable || parentClickable
        val text = node.text?.toString()
        // A starting container (like the web page itself) may carry a title; read its contents instead.
        if (!text.isNullOrBlank() && !(isStart && node.childCount > 0)) {
            if (cls.contains("EditText") && text.length < 80) return
            if (isAdLabel(text)) return
            val rect = bounds(node)
            if (!ctx.inWeb && clickable && text.length <= 30 && inEdgeBand(rect, ctx.window)) return
            out += Raw(text, isCode = false, rect = rect)
            return
        }

        val count = node.childCount
        if (count == 0) {
            val description = node.contentDescription?.toString()
            if (!description.isNullOrBlank() && !node.isClickable && !isAdLabel(description)) {
                out += Raw(description, isCode = false, rect = bounds(node))
            }
            return
        }
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            walk(child, out, ctx, depth + 1, isStart = false, parentClickable = clickable)
        }
    }

    private fun findWebRoots(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > MAX_DEPTH || !node.isVisibleToUser) return
        if (node.className?.toString() == WEBVIEW) {
            out += node
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findWebRoots(child, out, depth + 1)
        }
    }

    private fun findRole(node: AccessibilityNodeInfo, wanted: String, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (role(child) == wanted && child.isVisibleToUser) return child
            val found = findRole(child, wanted, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun subtreeText(node: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 30) return ""
        node.text?.toString()?.let { if (it.isNotBlank()) return it }
        val sb = StringBuilder()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val t = subtreeText(child, depth + 1)
            if (t.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(t)
            }
            if (sb.length > 400) break
        }
        return sb.toString()
    }

    /** Chrome-based browsers expose the HTML role (main, navigation, image, ...) in extras. */
    private fun role(node: AccessibilityNodeInfo): String? {
        val raw = node.extras?.getCharSequence(CHROME_ROLE)?.toString() ?: return null
        val name = if (raw.length > 1 && raw[0] == 'k' && raw[1].isUpperCase()) raw.substring(1) else raw
        return name.lowercase()
    }

    private fun isImage(cls: String) = cls.contains("Image")

    private fun isControl(cls: String): Boolean {
        val simple = cls.substringAfterLast('.')
        return simple.endsWith("Button") || simple.endsWith("CheckBox") || simple.endsWith("Switch") ||
            simple.endsWith("SeekBar") || simple.endsWith("ProgressBar") || simple.endsWith("Spinner") ||
            simple.endsWith("RatingBar") || simple.contains("Toolbar") || simple.contains("ActionBar") ||
            simple.contains("TabLayout") || simple.contains("TabWidget") || simple.contains("BottomNavigation") ||
            simple.contains("NavigationBar") || simple.contains("NavigationRail") || simple == "Chip"
    }

    private fun isAdLabel(text: String) = text.trim().lowercase() in adLabels

    private fun inEdgeBand(rect: Rect, window: Rect): Boolean {
        val band = window.height() / 9
        return rect.bottom <= window.top + band || rect.top >= window.bottom - band
    }

    private fun sameLine(a: Rect, b: Rect) = b.top < a.bottom - 4 && b.bottom > a.top + 4

    fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }
}

/** Works out which on-screen text is new after a scroll (screens overlap a little). */
object Overlap {
    fun fresh(previous: List<String>, next: List<Chunk>, lastScreen: Collection<String> = emptyList()): List<Chunk> {
        if (previous.isEmpty() || next.isEmpty()) return next
        val texts = next.map { it.text }
        var bestK = 0
        var bestEnd = -1
        // Allow a few repeated items at the top (like a sticky header) before the overlap.
        for (start in 0..minOf(4, texts.size - 1)) {
            val maxK = minOf(previous.size, texts.size - start)
            for (k in maxK downTo 1) {
                if (k <= bestK) break
                val match = previous.subList(previous.size - k, previous.size) == texts.subList(start, start + k)
                if (match) {
                    val strong = k >= 2 || texts[start].length >= 20
                    if (strong) {
                        bestK = k
                        bestEnd = start + k
                    }
                    break
                }
            }
        }
        if (bestEnd >= 0) return next.drop(bestEnd)
        val seen = lastScreen.toHashSet()
        return next.filter { it.isCode || it.text !in seen }
    }
}
