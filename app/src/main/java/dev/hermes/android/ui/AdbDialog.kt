package dev.hermes.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.hermes.android.device.AdbBridge
import dev.hermes.android.device.DevicePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Pairs and connects the phone's own Wireless ADB, so the agent can run shell commands on this device.
 * Both steps need the numbers Android shows in Developer options > Wireless debugging; the app cannot
 * read them, so they are typed here.
 */
@Composable
fun AdbDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("127.0.0.1") }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var log by remember {
        mutableStateOf(
            if (AdbBridge.available()) {
                "Ative \"Depuração sem fio\" nas opções do desenvolvedor e toque em \"Parear com código\"."
            } else {
                "Este APK não traz o adb para esta arquitetura."
            },
        )
    }

    // Runs one bridge call off the UI thread and shows its output, success or failure.
    fun perform(call: () -> String) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { JSONObject(call()) }
            val prefix = if (result.optBoolean("ok")) "OK" else "Falhou"
            log = "$prefix\n" + result.optString("output").ifBlank { "(sem saída)" }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Depuração sem fio") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("1. Pareamento: use a porta e o código de \"Parear com código\".", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("Endereço") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pairPort,
                    onValueChange = { pairPort = it.filter(Char::isDigit) },
                    label = { Text("Porta de pareamento") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Código de 6 dígitos") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { perform { AdbBridge.pair("$host:$pairPort", code) } },
                    enabled = !busy && AdbBridge.available() && pairPort.isNotBlank() && code.length == 6,
                ) { Text("Parear") }

                Text(
                    "2. Conexão: use a porta mostrada na tela principal de Depuração sem fio.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = connectPort,
                    onValueChange = { connectPort = it.filter(Char::isDigit) },
                    label = { Text("Porta de conexão") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { perform { AdbBridge.connect("$host:$connectPort") } },
                    enabled = !busy && AdbBridge.available() && connectPort.isNotBlank(),
                ) { Text("Conectar") }

                TextButton(onClick = { context.startActivity(DevicePermissions.developerSettings()) }) {
                    Text("Abrir opções do desenvolvedor")
                }
                Text(log, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
