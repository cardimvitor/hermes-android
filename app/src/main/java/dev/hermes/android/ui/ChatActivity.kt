package dev.hermes.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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

@Composable
fun ChatScreen(model: ChatViewModel = viewModel()) {
    val messages by model.messages.collectAsState()
    val status by model.status.collectAsState()
    val busy by model.busy.collectAsState()
    val approval by model.approval.collectAsState()
    var draft by remember { mutableStateOf("") }
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

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(16.dp)) {
        Text(text = status, style = MaterialTheme.typography.labelMedium)

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
                    Text(
                        text = message.text.ifEmpty { "…" },
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }

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
