package com.textreader.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * One piece of text found on screen, in reading order.
 * [isAction] marks a row of Like / Share / Copy buttons (the end of a post or answer);
 * [top] is its position on screen.
 */
data class Chunk(
    val text: String,
    val isCode: Boolean,
    val joinPrev: Boolean,
    val isAction: Boolean = false,
    val top: Int = 0,
)

data class Screen(val packageName: String, val chunks: List<Chunk>, val window: Rect = Rect()) {
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

    // Words that label the action buttons under a post or an AI answer.
    private val actionWords = setOf(
        "like", "likes", "liked", "unlike", "love", "react", "reaction", "reactions",
        "comment", "comments", "reply", "replies", "share", "shares", "repost", "reposts",
        "retweet", "retweets", "quote", "bookmark", "bookmarks", "save", "saved",
        "copy", "copied", "upvote", "downvote", "upvoted", "downvoted", "award", "awards",
        "helpful", "unhelpful", "regenerate", "retry", "thumbs", "analytics", "views",
    )
    private val actionPhrases = listOf(
        "good response", "bad response", "read aloud", "give feedback", "more actions",
        "thumbs up", "thumbs down", "not helpful",
    )

    const val ACTION_MARK = "\u0001ACTION\u0001"

    private class Ctx(val inWeb: Boolean, val window: Rect, val stopAtActions: Boolean)
    private class Raw(val text: String, val isCode: Boolean, val rect: Rect, val isAction: Boolean = false)

    fun isBrowser(packageName: String) = packageName in browsers

    fun extract(root: AccessibilityNodeInfo, stopAtActions: Boolean = true): Screen {
        val pkg = root.packageName?.toString().orEmpty()
        val window = bounds(root)
        val out = ArrayList<Raw>()

        if (isBrowser(pkg)) {
            // Only the web page itself, not the browser's address bar, tabs or menus.
            val webRoots = ArrayList<AccessibilityNodeInfo>()
            findWebRoots(root, webRoots, 0)
            if (webRoots.isEmpty()) {
                walk(root, out, Ctx(inWeb = true, window = window, stopAtActions = stopAtActions), 0, isStart = true, parentClickable = false)
            } else {
                for (web in webRoots) {
                    val main = findRole(web, "main", 0)
                    walk(main ?: web, out, Ctx(inWeb = true, window = window, stopAtActions = stopAtActions), 0, isStart = true, parentClickable = false)
                }
            }
        } else {
            walk(root, out, Ctx(inWeb = false, window = window, stopAtActions = stopAtActions), 0, isStart = true, parentClickable = false)
        }

        val items = groupActionRows(out)
        val chunks = items.mapIndexed { i, raw ->
            val prev = if (i > 0) items[i - 1] else null
            val join = prev != null && !raw.isCode && !raw.isAction && !prev.isCode && !prev.isAction &&
                sameLine(prev.rect, raw.rect)
            if (raw.isAction) {
                Chunk(ACTION_MARK, isCode = false, joinPrev = false, isAction = true, top = raw.rect.top)
            } else {
                Chunk(raw.text.trim(), raw.isCode, join)
            }
        }.filter { it.isCode || it.isAction || it.text.isNotEmpty() }
        return Screen(pkg, chunks, window)
    }

    /**
     * Two or more action buttons on one line (with only small counts like "1.2K" between them)
     * become one action-row marker. A single button (like a code block's Copy) is not a row.
     */
    private fun groupActionRows(raw: List<Raw>): List<Raw> {
        val result = ArrayList<Raw>()
        var i = 0
        while (i < raw.size) {
            val first = raw[i]
            if (!first.isAction) {
                result += first
                i++
                continue
            }
            var j = i
            var actions = 0
            var lastAction = first.rect
            val between = ArrayList<Raw>()
            while (j < raw.size) {
                val item = raw[j]
                if (item.isAction && (actions == 0 || sameLine(lastAction, item.rect))) {
                    actions++
                    lastAction = item.rect
                    j++
                } else if (!item.isAction && !item.isCode && item.text.trim().length <= 12 && sameLine(lastAction, item.rect)) {
                    between += item
                    j++
                } else {
                    break
                }
            }
            if (actions >= 2) {
                result += Raw("", isCode = false, rect = first.rect, isAction = true)
            } else {
                result.addAll(between)
            }
            i = j
        }
        return result
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

        if (!isStart && ctx.stopAtActions && isActionButton(node, cls, role)) {
            out += Raw("", isCode = false, rect = bounds(node), isAction = true)
            return
        }

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

    /** A clickable button whose label means like / share / copy / comment and so on. */
    private fun isActionButton(node: AccessibilityNodeInfo, cls: String, role: String?): Boolean {
        val buttonLike = node.isClickable || role == "button" || role == "togglebutton" ||
            cls.endsWith("Button") || cls.endsWith("ImageButton")
        if (!buttonLike) return false
        val label = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: subtreeText(node, 0).takeIf { it.length in 1..40 }
            ?: return false
        return isActionLabel(label)
    }

    private fun isActionLabel(label: String): Boolean {
        val lower = label.lowercase().trim()
        if (lower.isEmpty() || lower.length > 70) return false
        if (actionPhrases.any { lower.contains(it) }) return true
        val words = Regex("[a-z]+").findAll(lower).map { it.value }.toList()
        if (words.isEmpty() || words.size > 8) return false
        return words.any { it in actionWords }
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
        return next.filter { it.isCode || it.isAction || it.text !in seen }
    }
}
