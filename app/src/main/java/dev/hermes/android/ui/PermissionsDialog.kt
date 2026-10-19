package dev.hermes.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.hermes.android.device.DevicePermissions

private data class PermissionRow(val title: String, val detail: String, val granted: Boolean, val onGrant: () -> Unit)

/**
 * What the agent needs and whether it has it. The special permissions cannot be granted by the app itself,
 * so "Conceder" opens the right Settings screen; the statuses are read again when the user comes back.
 */
@Composable
fun PermissionsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    // `refresh` is the key, so the list (and every status in it) is rebuilt after each return from Settings.
    val rows = remember(refresh) {
        listOf(
            PermissionRow(
                "Notificações",
                "Mostra a notificação do agente em execução.",
                DevicePermissions.notificationsGranted(context),
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(DevicePermissions.notificationSettings(context))
                }
            },
            PermissionRow(
                "Acessibilidade",
                "Ler a tela e tocar, digitar e rolar em outros apps. Ligue o Hermes em Acessibilidade.",
                DevicePermissions.accessibilityConnected(),
            ) { context.startActivity(DevicePermissions.accessibilitySettings()) },
            PermissionRow(
                "Todos os arquivos",
                "Ler e gravar em Downloads, DCIM e outras pastas compartilhadas.",
                DevicePermissions.allFilesGranted(),
            ) { context.startActivity(DevicePermissions.allFilesSettings(context)) },
            PermissionRow(
                "Sem restrição de bateria",
                "Evita que o Android pare o agente em segundo plano.",
                DevicePermissions.batteryUnrestricted(context),
            ) { context.startActivity(DevicePermissions.batterySettings(context)) },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissões") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { row ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = row.title,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (row.granted) "✓ Concedida" else "Pendente",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Text(text = row.detail, style = MaterialTheme.typography.bodySmall)
                        if (!row.granted) {
                            TextButton(onClick = row.onGrant) { Text("Conceder") }
                        }
                    }
                }
                Text(
                    text = "Acessibilidade bloqueada? Toque nela uma vez, feche o aviso e abra os detalhes do app: " +
                        "menu ⋮ → Permitir configurações restritas.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = { context.startActivity(DevicePermissions.appDetails(context)) }) {
                    Text("Abrir detalhes do app")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
