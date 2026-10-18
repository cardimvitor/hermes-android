package dev.hermes.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hermes.android.gateway.GatewayClient
import dev.hermes.android.gateway.GatewayService
import dev.hermes.android.gateway.HermesRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMessage(val fromUser: Boolean, val text: String)

/** A dangerous command the agent wants to run. The user picks "once" or "deny". */
data class PendingApproval(val requestId: String, val command: String, val description: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _status = MutableStateFlow("Iniciando agente…")
    val status: StateFlow<String> = _status

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval

    private var gateway: GatewayClient? = null
    private var sessionId: String? = null

    init {
        GatewayService.start(app)
        viewModelScope.launch {
            val client = withContext(Dispatchers.IO) { HermesRuntime.client(app) }
            gateway = client
            launch { client.events.collect { handleEvent(it) } }
            launch {
                client.requests.collect { request ->
                    if (request.method == "approval") {
                        _approval.value = PendingApproval(
                            requestId = request.id,
                            command = request.params.optString("command"),
                            description = request.params.optString("description"),
                        )
                    }
                }
            }
            // Without this the gateway never asks for approval and denies dangerous commands silently.
            client.announceCapabilities()

            val response = withContext(Dispatchers.IO) {
                client.call("session.create", JSONObject().put("cols", 80)).get(60, TimeUnit.SECONDS)
            }
            val id = response.optJSONObject("result")?.optString("session_id").orEmpty()
            sessionId = id.ifEmpty { null }
            _status.value = if (id.isEmpty()) errorText(response, "Falha ao criar sessão") else "Pronto"
        }
    }

    fun send(text: String) {
        val id = sessionId ?: return
        val client = gateway ?: return
        _busy.value = true
        _messages.update { it + ChatMessage(true, text) + ChatMessage(false, "") }
        viewModelScope.launch {
            val response = withContext(Dispatchers.IO) {
                client.call("prompt.submit", JSONObject().put("session_id", id).put("text", text))
                    .get(10, TimeUnit.MINUTES)
            }
            if (response.has("error")) {
                _busy.value = false
                _messages.update { it.dropLast(1) + ChatMessage(false, errorText(response, "Erro")) }
            }
        }
    }

    /** [choice] is one of the gateway's ApprovalChoice values: "once", "session", "always" or "deny". */
    fun answerApproval(choice: String) {
        val pending = _approval.value ?: return
        gateway?.respond(pending.requestId, JSONObject().put("choice", choice))
        _approval.value = null
    }

    private fun handleEvent(event: JSONObject) {
        if (event.optString("session_id") != sessionId) return
        val payload = event.optJSONObject("payload")
        when (event.optString("type")) {
            "message.delta" -> {
                val text = payload?.takeIf { !it.isNull("text") }?.getString("text") ?: return
                _messages.update { list ->
                    val last = list.lastOrNull()
                    if (last == null || last.fromUser) list + ChatMessage(false, text)
                    else list.dropLast(1) + last.copy(text = last.text + text)
                }
            }
            "message.complete" -> _busy.value = false
        }
    }

    private fun errorText(response: JSONObject, fallback: String): String =
        response.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: fallback
}
