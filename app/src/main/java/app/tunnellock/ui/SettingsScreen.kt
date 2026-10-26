package app.tunnellock.ui

import android.app.LocaleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunnellock.BuildConfig
import app.tunnellock.R
import app.tunnellock.data.AppLog
import app.tunnellock.vpn.XrayCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val autoConnect by vm.store.autoConnect.collectAsStateWithLifecycle()
    val pauseWhenDisconnected by vm.store.pauseWhenDisconnected.collectAsStateWithLifecycle()
    val xrayVersion by produceState("…") {
        value = withContext(Dispatchers.IO) { XrayCore.version(context) }
    }
    var showLogs by remember { mutableStateOf(false) }
    if (showLogs) LogsDialog(onDismiss = { showLogs = false })

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_auto_connect)) },
            supportingContent = { Text(stringResource(R.string.settings_auto_connect_desc)) },
            trailingContent = { Switch(checked = autoConnect, onCheckedChange = vm::setAutoConnect) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_pause)) },
            supportingContent = { Text(stringResource(R.string.settings_pause_desc)) },
            trailingContent = { Switch(checked = pauseWhenDisconnected, onCheckedChange = vm::setPauseWhenDisconnected) },
        )
        HorizontalDivider()
        if (Build.VERSION.SDK_INT >= 33) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { setLanguage(context, "fa") }) { Text("فارسی") }
                        TextButton(onClick = { setLanguage(context, "en") }) { Text("English") }
                    }
                },
            )
            HorizontalDivider()
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_reapply)) },
            supportingContent = { Text(stringResource(R.string.settings_reapply_desc)) },
            modifier = Modifier.clickable { vm.reapplyPolicies() },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_unknown_sources)) },
            supportingContent = { Text(stringResource(R.string.settings_unknown_sources_desc)) },
            modifier = Modifier.clickable {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                )
            },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_logs)) },
            supportingContent = { Text(stringResource(R.string.settings_logs_desc)) },
            modifier = Modifier.clickable { showLogs = true },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_version)) },
            supportingContent = { Text(ltr("TunnelLock ${BuildConfig.VERSION_NAME}\n$xrayVersion")) },
        )
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_how_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_how_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun setLanguage(context: android.content.Context, tag: String) {
    if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
    }
}

@Composable
private fun LogsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lines by AppLog.lines.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_logs)) },
        text = {
            if (lines.isEmpty()) {
                Text(stringResource(R.string.logs_empty))
            } else {
                // Logs are English and technical: keep them left-to-right.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(lines.reversed()) { line ->
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("TunnelLock log", AppLog.text()))
                onDismiss()
            }) { Text(stringResource(R.string.logs_copy)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.logs_close)) } },
    )
}
