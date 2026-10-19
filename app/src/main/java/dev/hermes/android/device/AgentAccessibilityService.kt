package dev.hermes.android.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lets the agent read the active window and act on it: taps, text entry, scrolls and global keys.
 * The user turns it on in Settings > Accessibility; nothing is read or performed until then.
 */
class AgentAccessibilityService : AccessibilityService() {

    /** Nodes from the last snapshot. Agent indexes are 1-based positions in this list. */
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Visible, meaningful nodes of the active window, in reading order. */
    @Synchronized
    fun snapshot(): JSONObject {
        val root = rootInActiveWindow
        val json = JSONArray()
        val collected = mutableListOf<AccessibilityNodeInfo>()
        if (root != null) {
            collect(root, collected, json)
        }
        nodes = collected
        return JSONObject()
            .put("app", root?.packageName?.toString().orEmpty())
            .put("nodes", json)
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, json: JSONArray) {
        val interactive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
        val labelled = !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()
        if (node.isVisibleToUser && (interactive || labelled)) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out += node
            json.put(
                JSONObject()
                    .put("index", out.size)
                    .put("class", node.className?.toString().orEmpty())
                    .put("text", node.text?.toString().orEmpty())
                    .put("desc", node.contentDescription?.toString().orEmpty())
                    .put("id", node.viewIdResourceName.orEmpty())
                    .put("clickable", node.isClickable)
                    .put("editable", node.isEditable)
                    .put("scrollable", node.isScrollable)
                    .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.width(), rect.height()))),
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out, json) }
        }
    }

    /** Taps the node at [index], or its nearest clickable ancestor. */
    @Synchronized
    fun click(index: Int): Boolean {
        var target: AccessibilityNodeInfo? = nodes.getOrNull(index - 1) ?: return false
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        return (target ?: nodes[index - 1]).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Taps a screen point (logical pixels). */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Sets the text of the node at [index], or of the focused input when [index] is null. */
    @Synchronized
    fun setText(index: Int?, text: String): Boolean {
        val node = if (index != null) {
            nodes.getOrNull(index - 1)
        } else {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Scrolls the node at [index], or the first scrollable node of the last snapshot. */
    @Synchronized
    fun scroll(index: Int?, forward: Boolean): Boolean {
        val node = if (index != null) nodes.getOrNull(index - 1) else nodes.firstOrNull { it.isScrollable }
        node ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node.performAction(action)
    }

    /** back, home, recents or notifications. */
    fun global(name: String): Boolean = when (name) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        else -> false
    }

    companion object {
        /** Set while the service is connected; null when the user has not enabled it. */
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }
}
