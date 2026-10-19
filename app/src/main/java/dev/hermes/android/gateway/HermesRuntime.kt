package dev.hermes.android.gateway

import android.content.Context
import com.chaquo.python.Python
import java.io.File

/** Receives every outgoing RPC frame. Chaquopy calls [onLine] on a Python-owned thread. */
interface LineSink {
    fun onLine(line: String)
}

/** Owns the single in-process gateway. Creating it imports the Hermes Python package (slow). */
object HermesRuntime {

    private const val DEFAULT_PROVIDER = "deepseek"
    private const val DEFAULT_MODEL = "deepseek-flash"

    private val lock = Any()
    private var gateway: GatewayClient? = null

    /** Starts the gateway on first use. Blocking: call it from a background thread, never the UI thread. */
    fun client(context: Context): GatewayClient = synchronized(lock) {
        gateway ?: start(context.applicationContext).also { gateway = it }
    }

    private fun start(app: Context): GatewayClient {
        val bridge = Python.getInstance().getModule("hermes_bridge")
        val client = GatewayClient(send = { line -> bridge.callAttr("send", line) })
        val sink = object : LineSink {
            override fun onLine(line: String) = client.onLine(line)
        }
        // Private app storage: sessions, memory, skills and credentials live under filesDir.
        val home = File(app.filesDir, "hermes").apply { mkdirs() }.absolutePath
        // First run only: without a provider the gateway falls back to AWS Bedrock, which the APK
        // does not ship. Provider and key selection in the UI is still to be built (plan, phase 5).
        bridge.callAttr("configure_model", home, DEFAULT_PROVIDER, DEFAULT_MODEL)
        // Keys are encrypted at rest (ApiKeyStore). The gateway reads them only at start.
        val deepseek = ApiKeyStore.get(app, ApiKeyStore.DEEPSEEK)
        if (deepseek.isNotEmpty()) bridge.callAttr("set_env", ApiKeyStore.DEEPSEEK, deepseek)
        bridge.callAttr("start", home, sink, null)
        return client
    }
}
