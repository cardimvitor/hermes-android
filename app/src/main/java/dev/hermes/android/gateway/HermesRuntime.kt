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
        val home = File(app.filesDir, "hermes").apply { mkdirs() }
        bridge.callAttr("start", home.absolutePath, sink)
        return client
    }
}
