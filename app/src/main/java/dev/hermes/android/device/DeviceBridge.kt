package dev.hermes.android.device

import android.content.Intent
import dev.hermes.android.HermesApplication
import org.json.JSONArray
import org.json.JSONObject

/**
 * Static entry points for the Python side (called through Chaquopy's `java` module). Results are JSON
 * strings or primitives so the Python code never touches Android types directly.
 */
object DeviceBridge {

    @JvmStatic
    fun isServiceConnected(): Boolean = AgentAccessibilityService.instance != null

    /** Launchable apps as a JSON array of {"name", "package"}. */
    @JvmStatic
    fun listApps(): String {
        val context = HermesApplication.appContext ?: return "[]"
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map { info ->
            JSONObject()
                .put("name", info.loadLabel(pm).toString())
                .put("package", info.activityInfo.packageName)
        }
        return JSONArray(apps).toString()
    }

    /** Opens an app. Goes through the accessibility service when it is connected, since that may start activities from the background. */
    @JvmStatic
    fun launchApp(packageName: String): Boolean {
        val context = HermesApplication.appContext ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val starter = AgentAccessibilityService.instance ?: context
        starter.startActivity(intent)
        return true
    }

    /** Active window as JSON {"app", "nodes": [...]}. Empty nodes with "error" when the service is off. */
    @JvmStatic
    fun snapshot(): String {
        val service = AgentAccessibilityService.instance
            ?: return JSONObject().put("app", "").put("nodes", JSONArray()).put("error", "accessibility_off").toString()
        return service.snapshot().toString()
    }

    /** Screen size in logical pixels as "width,height". */
    @JvmStatic
    fun screenSize(): String {
        val metrics = HermesApplication.appContext?.resources?.displayMetrics ?: return "0,0"
        return "${metrics.widthPixels},${metrics.heightPixels}"
    }

    @JvmStatic
    fun click(index: Int): Boolean = AgentAccessibilityService.instance?.click(index) ?: false

    @JvmStatic
    fun tap(x: Float, y: Float): Boolean = AgentAccessibilityService.instance?.tap(x, y) ?: false

    /** [index] of -1 targets the focused input. */
    @JvmStatic
    fun setText(index: Int, text: String): Boolean =
        AgentAccessibilityService.instance?.setText(if (index < 0) null else index, text) ?: false

    /** [index] of -1 scrolls the first scrollable node of the last snapshot. */
    @JvmStatic
    fun scroll(index: Int, forward: Boolean): Boolean =
        AgentAccessibilityService.instance?.scroll(if (index < 0) null else index, forward) ?: false

    @JvmStatic
    fun globalAction(name: String): Boolean = AgentAccessibilityService.instance?.global(name) ?: false
}
