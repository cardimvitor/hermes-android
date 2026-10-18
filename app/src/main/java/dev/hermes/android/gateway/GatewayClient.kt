package dev.hermes.android.gateway

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A question the agent asks the app (approval, clarify, sudo, ...). Answer it with [GatewayClient.respond]. */
data class ServerRequest(val id: String, val method: String, val params: JSONObject)

/**
 * JSON-RPC 2.0 client for the Python `tui_gateway`.
 *
 * [send] delivers one request line to the gateway. [onLine] receives every frame the gateway
 * writes; it is called on the Python thread, so it only completes futures and emits to flows.
 */
class GatewayClient(private val send: (String) -> Unit) {

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val eventFlow = MutableSharedFlow<JSONObject>(extraBufferCapacity = 256)
    private val requestFlow = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 32)

    /** `params` of every `method == "event"` frame: `{type, session_id?, payload?}`. */
    val events: SharedFlow<JSONObject> = eventFlow

    /** Questions the agent is waiting on. Unanswered requests fail on the gateway side after a deadline. */
    val requests: SharedFlow<ServerRequest> = requestFlow

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

    /**
     * Declares that this client answers server→client requests. Without it the gateway does not
     * send them, and dangerous commands would be denied without asking the user.
     */
    fun announceCapabilities(): CompletableFuture<JSONObject> =
        call("client.capabilities", JSONObject().put("server_requests", true))

    /** Answers a [ServerRequest]. `result` follows the method's contract (e.g. `{"choice": "once"}`). */
    fun respond(requestId: String, result: JSONObject) {
        val response = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
        send(response.toString())
    }

    fun onLine(line: String) {
        // Anything that is not JSON on the RPC channel (a stray print) is ignored, not fatal.
        val frame = try {
            JSONObject(line)
        } catch (e: JSONException) {
            return
        }
        val method = frame.optString("method")
        when {
            frame.has("id") && (frame.has("result") || frame.has("error")) -> {
                pending.remove(frame.optLong("id", -1L))?.complete(frame)
            }
            method == "event" -> eventFlow.tryEmit(frame.getJSONObject("params"))
            method.isNotEmpty() && frame.optString("id").startsWith("srq-") -> {
                requestFlow.tryEmit(ServerRequest(frame.getString("id"), method, frame.optJSONObject("params") ?: JSONObject()))
            }
        }
    }
}
