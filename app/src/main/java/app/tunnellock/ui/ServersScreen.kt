package app.tunnellock.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunnellock.R
import app.tunnellock.core.model.Protocol
import app.tunnellock.data.StoredServer
import app.tunnellock.data.Subscription
import app.tunnellock.data.SubscriptionUsage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.Locale

@Composable
fun ServersScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val servers by vm.store.servers.collectAsStateWithLifecycle()
    val subscriptions by vm.store.subscriptions.collectAsStateWithLifecycle()
    val selectedId by vm.store.selectedId.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val updating by vm.updating.collectAsStateWithLifecycle()
    val loginRequest by vm.loginRequest.collectAsStateWithLifecycle()
    val sshForm by vm.sshForm.collectAsStateWithLifecycle()

    var showPaste by remember { mutableStateOf(false) }
    var showSubscription by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<StoredServer?>(null) }
    var subToDelete by remember { mutableStateOf<Subscription?>(null) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(vm::importText)
    }
    // .ovpn files have no reliable MIME type, so any file can be picked.
    val ovpnPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importOvpnFile)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = {
                    val text = clipboardText(context)
                    if (text.isNullOrBlank()) showPaste = true else vm.importText(text)
                },
                label = { Text(stringResource(R.string.servers_paste)) },
            )
            AssistChip(onClick = { showPaste = true }, label = { Text(stringResource(R.string.servers_text)) })
            AssistChip(
                onClick = {
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                            .setPrompt(""),
                    )
                },
                label = { Text(stringResource(R.string.servers_scan)) },
            )
            AssistChip(onClick = { showSubscription = true }, label = { Text(stringResource(R.string.servers_subscription)) })
            AssistChip(onClick = { ovpnPicker.launch(arrayOf("*/*")) }, label = { Text(stringResource(R.string.servers_ovpn_file)) })
            AssistChip(onClick = vm::newSsh, label = { Text(stringResource(R.string.servers_ssh)) })
            AssistChip(
                onClick = vm::testAll,
                enabled = !testing && servers.isNotEmpty(),
                label = { Text(stringResource(R.string.servers_test)) },
            )
        }
        if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (servers.isEmpty() && subscriptions.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.servers_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (subscriptions.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.servers_subscriptions)) }
                    items(subscriptions, key = { "sub-" + it.id }) { sub ->
                        SubscriptionRow(
                            sub = sub,
                            busy = sub.id in updating,
                            onUpdate = { vm.updateSubscription(sub) },
                            onDelete = { subToDelete = sub },
                        )
                    }
                }
                item { SectionTitle(stringResource(R.string.servers_list, servers.size)) }
                items(servers, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        selected = server.id == selectedId,
                        onClick = { vm.select(server) },
                        onEditLogin = when (server.protocol) {
                            Protocol.OPENVPN.label -> { { vm.editLogin(server) } }
                            Protocol.SSH.label -> { { vm.editSsh(server) } }
                            else -> null
                        },
                        onDelete = { toDelete = server },
                    )
                }
            }
        }
    }

    if (showPaste) {
        PasteDialog(onDismiss = { showPaste = false }, onImport = {
            vm.importText(it)
            showPaste = false
        })
    }
    if (showSubscription) {
        SubscriptionDialog(onDismiss = { showSubscription = false }, onAdd = { name, url ->
            vm.addSubscription(name, url)
            showSubscription = false
        })
    }
    sshForm?.let { form ->
        SshServerDialog(initial = form, onDismiss = vm::dismissSsh, onSave = vm::saveSsh)
    }
    loginRequest?.let { request ->
        OpenVpnLoginDialog(
            request = request,
            onDismiss = vm::dismissLogin,
            onSave = { name, user, password, key -> vm.submitLogin(request, name, user, password, key) },
        )
    }
    toDelete?.let { server ->
        ConfirmDialog(
            text = stringResource(R.string.servers_delete_confirm, server.name),
            onDismiss = { toDelete = null },
            onConfirm = {
                vm.delete(server)
                toDelete = null
            },
        )
    }
    subToDelete?.let { sub ->
        ConfirmDialog(
            text = stringResource(R.string.servers_delete_subscription_confirm, sub.name),
            onDismiss = { subToDelete = null },
            onConfirm = {
                vm.deleteSubscription(sub)
                subToDelete = null
            },
        )
    }
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ServerRow(
    server: StoredServer,
    selected: Boolean,
    onClick: () -> Unit,
    onEditLogin: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    ltr("${server.protocol} · ${server.address}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Latency(server.latencyMs)
            if (onEditLogin != null) {
                IconButton(onClick = onEditLogin) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.ovpn_edit_login))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.dialog_delete))
            }
        }
    }
}

@Composable
private fun usageText(usage: SubscriptionUsage): String {
    val used = gigabytes(usage.usedBytes)
    val volume = if (usage.totalBytes > 0) {
        stringResource(R.string.sub_usage_of, used, gigabytes(usage.totalBytes))
    } else {
        stringResource(R.string.sub_usage_unlimited, used)
    }
    if (usage.expiresAt <= 0) return volume
    val days = ((usage.expiresAt * 1000 - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
    return volume + " · " + stringResource(R.string.sub_days_left, days)
}

private fun gigabytes(bytes: Long) = ltr(String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0))

@Composable
private fun Latency(ms: Long?) {
    if (ms == null) return
    val (text, color) = when {
        ms < 0 -> stringResource(R.string.servers_latency_failed) to MaterialTheme.colorScheme.error
        ms < 800 -> ltr("$ms ms") to Color(0xFF2E7D32)
        else -> ltr("$ms ms") to Color(0xFFEF8F00)
    }
    Text(text, color = color, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun SubscriptionRow(sub: Subscription, busy: Boolean, onUpdate: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sub.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    ltr(sub.url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                sub.usage?.let { usage -> Text(usageText(usage), style = MaterialTheme.typography.bodySmall) }
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onUpdate) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.servers_update))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.dialog_delete))
            }
        }
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_paste_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 4,
                maxLines = 10,
                placeholder = { Text(stringResource(R.string.dialog_paste_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onImport(text) }) { Text(stringResource(R.string.dialog_import)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun SubscriptionDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_sub_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dialog_sub_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dialog_sub_url)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, url) }, enabled = url.startsWith("http")) {
                Text(stringResource(R.string.dialog_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun OpenVpnLoginDialog(
    request: LoginRequest,
    onDismiss: () -> Unit,
    onSave: (name: String, username: String, password: String, keyPassword: String) -> Unit,
) {
    val existing = request.server
    var name by remember(request) { mutableStateOf(request.name) }
    var username by remember(request) { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember(request) { mutableStateOf(existing?.password.orEmpty()) }
    var keyPassword by remember(request) { mutableStateOf(existing?.keyPassword.orEmpty()) }
    val ready = (!request.needsUser || username.isNotBlank() && password.isNotEmpty()) &&
        (!request.needsKey || keyPassword.isNotEmpty())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ovpn_login_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.ovpn_login_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (request.parsed != null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.dialog_sub_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (request.needsUser) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.ovpn_username)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PasswordField(password, { password = it }, stringResource(R.string.ovpn_password))
                }
                if (request.needsKey) {
                    PasswordField(keyPassword, { keyPassword = it }, stringResource(R.string.ovpn_key_password))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, username, password, keyPassword) }, enabled = ready) {
                Text(stringResource(R.string.dialog_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

/** An SSH account: address, login, and how the connection reaches the server. */
@Composable
private fun SshServerDialog(initial: SshForm, onDismiss: () -> Unit, onSave: (SshForm) -> Unit) {
    var form by remember(initial) { mutableStateOf(initial) }
    val transports = listOf(
        "tcp" to R.string.ssh_transport_tcp,
        "tls" to R.string.ssh_transport_tls,
        "ws" to R.string.ssh_transport_ws,
        "wss" to R.string.ssh_transport_wss,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.ssh_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field(form.name, { form = form.copy(name = it) }, stringResource(R.string.dialog_sub_name))
                Field(form.host, { form = form.copy(host = it.trim()) }, stringResource(R.string.ssh_host), KeyboardType.Uri)
                Field(form.port, { form = form.copy(port = it.filter(Char::isDigit).take(5)) }, stringResource(R.string.ssh_port), KeyboardType.Number)
                Field(form.username, { form = form.copy(username = it) }, stringResource(R.string.ovpn_username), KeyboardType.Email)
                PasswordField(form.password, { form = form.copy(password = it) }, stringResource(R.string.ovpn_password))
                OutlinedTextField(
                    value = form.privateKey,
                    onValueChange = { form = form.copy(privateKey = it) },
                    label = { Text(stringResource(R.string.ssh_key)) },
                    minLines = 2,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (form.privateKey.isNotBlank()) {
                    PasswordField(form.keyPassphrase, { form = form.copy(keyPassphrase = it) }, stringResource(R.string.ssh_key_passphrase))
                }
                Text(stringResource(R.string.ssh_transport), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    transports.forEach { (value, label) ->
                        FilterChip(
                            selected = form.transport == value,
                            onClick = { form = form.copy(transport = value) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                if (form.transport == "tls" || form.transport == "wss") {
                    Field(form.sni, { form = form.copy(sni = it.trim()) }, stringResource(R.string.ssh_sni), KeyboardType.Uri)
                }
                if (form.transport == "ws" || form.transport == "wss") {
                    Field(form.wsHost, { form = form.copy(wsHost = it.trim()) }, stringResource(R.string.ssh_ws_host), KeyboardType.Uri)
                    Field(form.path, { form = form.copy(path = it.trim()) }, stringResource(R.string.ssh_ws_path), KeyboardType.Uri)
                }
                Field(form.hostKey, { form = form.copy(hostKey = it.trim()) }, stringResource(R.string.ssh_hostkey))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(form) }, enabled = form.ready) { Text(stringResource(R.string.dialog_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun Field(value: String, onValueChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ConfirmDialog(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.dialog_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}
