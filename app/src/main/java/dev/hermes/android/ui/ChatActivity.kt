package dev.hermes.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen()
                }
            }
        }
    }
}

private const val VISIBLE_STEPS = 5

/**
 * Live summary of what the agent is doing: the current phase, then the last few tool calls with their
 * duration. Tap the header to collapse it. After the turn it stays as "Concluído: N passos" until the next send.
 */
@Composable
private fun ActivityPanel(phase: String, steps: List<ActivityStep>, busy: Boolean) {
    if (!busy && steps.isEmpty()) return
    var expanded by remember { mutableStateOf(true) }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            val title = if (busy) phase.ifEmpty { "Trabalhando…" } else "Concluído: ${steps.size} passo(s)"
            Text(
                text = title + if (expanded) "  ▲" else "  ▼",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            if (steps.size > VISIBLE_STEPS) {
                Text(
                    text = "… +${steps.size - VISIBLE_STEPS} anteriores",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            steps.takeLast(VISIBLE_STEPS).forEach { step ->
                val time = step.seconds?.let { "  (${"%.1f".format(it)}s)" }.orEmpty()
                Text(
                    text = (if (step.running) "…  " else "✓  ") + step.label + time,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/** A message the user sent, with a button that copies its text to the clipboard. */
@Composable
private fun UserMessage(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // The label goes back to "Copiar" after a moment.
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(text = text, modifier = Modifier.padding(8.dp))
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
        ) { Text(if (copied) "Copiado" else "Copiar") }
    }
}

@Composable
private fun ApiKeyDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chave DeepSeek") },
        text = {
            Column {
                Text("Guardada criptografada no aparelho. Reinicie o app depois de salvar.")
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun ChatScreen(model: ChatViewModel = viewModel()) {
    val messages by model.messages.collectAsState()
    val status by model.status.collectAsState()
    val busy by model.busy.collectAsState()
    val approval by model.approval.collectAsState()
    val phase by model.phase.collectAsState()
    val steps by model.steps.collectAsState()
    var draft by remember { mutableStateOf("") }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    approval?.let { pending ->
        AlertDialog(
            onDismissRequest = { model.answerApproval("deny") },
            title = { Text("Permitir este comando?") },
            text = {
                Column {
                    if (pending.description.isNotEmpty()) Text(pending.description)
                    Text(pending.command, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { model.answerApproval("once") }) { Text("Permitir uma vez") } },
            dismissButton = { TextButton(onClick = { model.answerApproval("deny") }) { Text("Negar") } },
        )
    }

    if (showPermissions) {
        PermissionsDialog(onDismiss = { showPermissions = false })
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            onSave = { key ->
                model.saveDeepseekKey(key)
                showKeyDialog = false
            },
            onDismiss = { showKeyDialog = false },
        )
    }

    // safeDrawing keeps the content clear of the status bar, navigation bar and keyboard (targetSdk 35 is edge-to-edge).
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showPermissions = true }) { Text("Permissões") }
            TextButton(onClick = { showKeyDialog = true }) { Text("Chave") }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
                ) {
                    if (message.fromUser) {
                        UserMessage(message.text)
                    } else {
                        Text(
                            text = message.text.ifEmpty { "…" },
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }

        ActivityPanel(phase = phase, steps = steps, busy = busy)

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensagem para o Hermes") },
                enabled = !busy,
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        model.send(text)
                        draft = ""
                    }
                },
                enabled = !busy && draft.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Enviar")
            }
        }
    }
}
