package dev.hermes.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hermes.android.HermesApplication
import dev.hermes.android.gateway.ApiKeyStore
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

/** One tool call of the current turn, shown in the live activity panel. */
data class ActivityStep(val id: String, val label: String, val running: Boolean, val seconds: Double? = null)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _status = MutableStateFlow("Iniciando agente…")
    val status: StateFlow<String> = _status

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval

    /** What the agent is doing right now ("Pensando…", "Executando: …"). Empty between turns. */
    private val _phase = MutableStateFlow("")
    val phase: StateFlow<String> = _phase

    /** Tool calls of the current turn, in order. Cleared when a new message is sent. */
    private val _steps = MutableStateFlow<List<ActivityStep>>(emptyList())
    val steps: StateFlow<List<ActivityStep>> = _steps

    private var gateway: GatewayClient? = null
    private var sessionId: String? = null

    init {
        GatewayService.start(app)
        viewModelScope.launch {
            // A Python failure here must show on screen, not close the app: the error text is the only
            // diagnostic available on a device without adb.
            val client = try {
                withContext(Dispatchers.IO) { HermesRuntime.client(app) }
            } catch (e: Throwable) {
                _status.value = "Falha ao iniciar: " + (HermesApplication.startError ?: e.toString())
                return@launch
            }
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
            _status.value = when {
                id.isEmpty() -> errorText(response, "Falha ao criar sessão")
                ApiKeyStore.get(app, ApiKeyStore.DEEPSEEK).isEmpty() -> "Sem chave de API: toque em Chave"
                else -> "Pronto"
            }
        }
    }

    /** Saves the DeepSeek key. The gateway reads keys at start, so the app must be restarted to use it. */
    fun saveDeepseekKey(key: String) {
        ApiKeyStore.save(getApplication(), ApiKeyStore.DEEPSEEK, key)
        _status.value = "Chave salva. Reinicie o app para usar."
    }

    fun send(text: String) {
        val id = sessionId ?: return
        val client = gateway ?: return
        _busy.value = true
        _steps.value = emptyList()
        _phase.value = "Enviando…"
        _messages.update { it + ChatMessage(true, text) + ChatMessage(false, "") }
        viewModelScope.launch {
            val response = withContext(Dispatchers.IO) {
                client.call("prompt.submit", JSONObject().put("session_id", id).put("text", text))
                    .get(10, TimeUnit.MINUTES)
            }
            if (response.has("error")) {
                _busy.value = false
                _phase.value = ""
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
            "message.start" -> _phase.value = "Pensando…"
            "thinking.delta", "reasoning.delta" -> {
                if (_steps.value.none { it.running }) _phase.value = "Pensando…"
            }
            "tool.generating" -> {
                _phase.value = "Preparando ${payload?.str("name").orEmpty().ifEmpty { "ferramenta" }}…"
            }
            "tool.start" -> if (payload != null) {
                val step = ActivityStep(payload.str("tool_id"), toolLabel(payload), running = true)
                _steps.update { it + step }
                _phase.value = "Executando: ${step.label}"
            }
            "tool.complete" -> if (payload != null) {
                val id = payload.str("tool_id")
                val seconds = if (payload.isNull("duration_s")) null else payload.optDouble("duration_s")
                _steps.update { list ->
                    if (list.any { it.id == id }) {
                        list.map { if (it.id == id) it.copy(running = false, seconds = seconds) else it }
                    } else {
                        list + ActivityStep(id, toolLabel(payload), running = false, seconds = seconds)
                    }
                }
                _phase.value = "Pensando…"
            }
            "message.delta" -> {
                val text = payload?.takeIf { !it.isNull("text") }?.getString("text") ?: return
                _phase.value = "Escrevendo resposta…"
                _messages.update { list ->
                    val last = list.lastOrNull()
                    if (last == null || last.fromUser) list + ChatMessage(false, text)
                    else list.dropLast(1) + last.copy(text = last.text + text)
                }
            }
            "message.complete" -> {
                _busy.value = false
                _phase.value = ""
                // The final text replaces the streamed deltas. On a failed turn (status "error") it is the
                // only place the reason arrives, so without this the reply bubble stays "..." forever.
                val text = payload?.optString("text").orEmpty()
                if (text.isNotEmpty()) {
                    _messages.update { list ->
                        val last = list.lastOrNull()
                        if (last == null || last.fromUser) list + ChatMessage(false, text)
                        else list.dropLast(1) + last.copy(text = text)
                    }
                }
            }
        }
    }

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

    /** "name: short preview" on one line, whitespace collapsed and capped for the activity panel. */
    private fun toolLabel(payload: JSONObject): String {
        val name = payload.str("name").ifEmpty { "ferramenta" }
        val detail = listOf("preview", "context", "args_text")
            .map { payload.str(it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
        val short = if (detail.length > 60) detail.take(60) + "…" else detail
        return if (short.isEmpty()) name else "$name: $short"
    }

    private fun errorText(response: JSONObject, fallback: String): String =
        response.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: fallback
}
