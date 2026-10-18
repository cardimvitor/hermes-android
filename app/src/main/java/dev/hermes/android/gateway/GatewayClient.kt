package dev.hermes.android.gateway

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * JSON-RPC 2.0 client for the Python `tui_gateway`.
 *
 * [send] delivers one request line to the gateway. [onLine] receives every frame the gateway
 * writes; it is called on the Python thread, so it only completes futures and emits to a flow.
 */
class GatewayClient(private val send: (String) -> Unit) {

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val eventFlow = MutableSharedFlow<JSONObject>(extraBufferCapacity = 256)

    /** `params` of every `method == "event"` frame: `{type, session_id?, payload?}`. */
    val events: SharedFlow<JSONObject> = eventFlow

    fun call(method: String, params: JSONObject = JSONObject()): CompletableFuture<JSONObject> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        send(request.toString())
        return future
    }

    fun onLine(line: String) {
        // Anything that is not JSON on the RPC channel (a stray print) is ignored, not fatal.
        val frame = try {
            JSONObject(line)
        } catch (e: JSONException) {
            return
        }
        if (frame.has("id") && (frame.has("result") || frame.has("error"))) {
            pending.remove(frame.optLong("id", -1L))?.complete(frame)
        } else if (frame.optString("method") == "event") {
            eventFlow.tryEmit(frame.getJSONObject("params"))
        }
        // Server-to-client requests (approval, clarify, secret) are not handled yet: see the plan.
    }
}
