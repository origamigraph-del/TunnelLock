package app.tunnellock.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunnellock.R
import app.tunnellock.data.StoredServer
import app.tunnellock.strict.CloneActivity
import app.tunnellock.strict.CloneQueue
import app.tunnellock.strict.CloneStatus
import app.tunnellock.vpn.Phase

/** Inside the secure space: the protected apps. New ones are picked on the personal side. */
@Composable
fun AppsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val apps by vm.protectedApps.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val queue by CloneQueue.items.collectAsStateWithLifecycle()
    var canInstall by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    var toRemove by remember { mutableStateOf<ProtectedApp?>(null) }
    var options by remember { mutableStateOf<String?>(null) }
    var pickServer by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        canInstall = context.packageManager.canRequestPackageInstalls()
        vm.refreshProtectedApps()
        onPauseOrDispose { }
    }
    LaunchedEffect(status.appsPaused, status.phase) { vm.refreshProtectedApps() }

    val pending = queue.count { it.status != CloneStatus.DONE && it.status != CloneStatus.FAILED }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.apps_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!canInstall) {
            item {
                NoticeCard(
                    text = stringResource(R.string.apps_install_permission),
                    isError = true,
                    action = stringResource(R.string.apps_open_settings),
                    onAction = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                        )
                    },
                )
            }
        }
        if (pending > 0) {
            item {
                NoticeCard(
                    text = stringResource(R.string.apps_pending, pending),
                    action = stringResource(R.string.apps_continue),
                    onAction = { context.startActivity(Intent(context, CloneActivity::class.java)) },
                )
            }
        }
        item {
            Button(onClick = { vm.openPhoneApps(context) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.apps_add_from_phone))
            }
        }
        val list = apps
        item { SectionTitle(stringResource(R.string.apps_protected, list?.size ?: 0)) }
        when {
            list == null -> item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            list.isEmpty() -> item {
                Text(stringResource(R.string.apps_protected_empty), style = MaterialTheme.typography.bodyMedium)
            }
            else -> items(list, key = { it.packageName }) { app ->
                val state = stringResource(
                    when {
                        app.paused -> R.string.apps_paused
                        status.phase == Phase.CONNECTED -> R.string.apps_running
                        else -> R.string.home_apps_blocked
                    },
                )
                val note = listOfNotNull(
                    stringResource(R.string.apps_never_pause_badge).takeIf { app.neverPause },
                    when {
                        app.ownServerMissing -> stringResource(R.string.apps_server_missing)
                        app.ownServer != null -> stringResource(R.string.apps_server_badge, app.ownServer)
                        else -> null
                    },
                ).joinToString("") { " · $it" }
                ListItem(
                    modifier = Modifier.clickable { options = app.packageName },
                    leadingContent = { AppIcon(app.info) },
                    headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(state + note) },
                    trailingContent = {
                        IconButton(onClick = { options = app.packageName }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.apps_options))
                        }
                    },
                )
            }
        }
    }

    // Looked up by package so the switch follows the refreshed list.
    apps?.find { it.packageName == options }?.let { app ->
        AppOptionsDialog(
            app = app,
            onNeverPause = { vm.setNeverPause(app, it) },
            onServer = {
                options = null
                pickServer = app.packageName
            },
            onRemove = {
                options = null
                toRemove = app
            },
            onDismiss = { options = null },
        )
    }

    apps?.find { it.packageName == pickServer }?.let { app ->
        ServerPickerDialog(
            app = app,
            choices = remember(app.packageName) { vm.appServerChoices() },
            mainIsOpenVpn = vm.mainIsOpenVpn(),
            onPick = { server ->
                pickServer = null
                vm.setAppServer(app, server)
            },
            onDismiss = { pickServer = null },
        )
    }

    toRemove?.let { app ->
        ConfirmDialog(
            text = stringResource(R.string.apps_remove_confirm, app.label),
            onDismiss = { toRemove = null },
            onConfirm = {
                vm.removeApp(context, app)
                toRemove = null
            },
        )
    }
}

@Composable
private fun AppOptionsDialog(
    app: ProtectedApp,
    onNeverPause: (Boolean) -> Unit,
    onServer: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIcon(app.info) },
        title = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                ListItem(
                    modifier = Modifier.clickable { onNeverPause(!app.neverPause) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.apps_never_pause)) },
                    supportingContent = { Text(stringResource(R.string.apps_never_pause_desc)) },
                    trailingContent = { Switch(checked = app.neverPause, onCheckedChange = onNeverPause) },
                )
                ListItem(
                    modifier = Modifier.clickable(onClick = onServer),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.apps_server)) },
                    supportingContent = {
                        Text(
                            when {
                                app.ownServerMissing -> stringResource(R.string.apps_server_missing)
                                app.ownServer != null -> app.ownServer
                                else -> stringResource(R.string.apps_server_main)
                            },
                        )
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) } },
        dismissButton = {
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.apps_remove_from_space), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

/** The app's own server: only through it, and no internet when it is down. */
@Composable
private fun ServerPickerDialog(
    app: ProtectedApp,
    choices: List<StoredServer>,
    mainIsOpenVpn: Boolean,
    onPick: (StoredServer?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_server)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.apps_server_desc, app.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mainIsOpenVpn) Warning(stringResource(R.string.apps_server_openvpn))
                if (Build.VERSION.SDK_INT < 29) Warning(stringResource(R.string.apps_server_old_android))
                ServerChoice(
                    label = stringResource(R.string.apps_server_main),
                    selected = app.ownServer == null && !app.ownServerMissing,
                    onClick = { onPick(null) },
                )
                choices.forEach { server ->
                    ServerChoice(
                        label = server.name,
                        selected = server.name == app.ownServer && !app.ownServerMissing,
                        onClick = { onPick(server) },
                    )
                }
                if (choices.isEmpty()) Text(stringResource(R.string.apps_server_none), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) } },
    )
}

@Composable
private fun Warning(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ServerChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}
