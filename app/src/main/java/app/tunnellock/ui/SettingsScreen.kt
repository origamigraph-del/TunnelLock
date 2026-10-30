package app.tunnellock.ui

import android.app.LocaleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunnellock.BuildConfig
import app.tunnellock.R
import app.tunnellock.data.UpdateChecker
import app.tunnellock.vpn.XrayCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val autoConnect by vm.store.autoConnect.collectAsStateWithLifecycle()
    val pauseWhenDisconnected by vm.store.pauseWhenDisconnected.collectAsStateWithLifecycle()
    val failover by vm.store.failover.collectAsStateWithLifecycle()
    val autoUpdate by vm.store.autoUpdateSubscriptions.collectAsStateWithLifecycle()
    val xrayVersion by produceState("…") {
        value = withContext(Dispatchers.IO) { XrayCore.version(context) }
    }
    val update by UpdateChecker.info.collectAsStateWithLifecycle()
    var notifyUpdates by remember { mutableStateOf(UpdateChecker.notificationsOn(context)) }
    var showLogs by remember { mutableStateOf(false) }
    if (showLogs) LogsDialog(onDismiss = { showLogs = false })
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleResumeEffect(Unit) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }

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
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_failover)) },
            supportingContent = { Text(stringResource(R.string.settings_failover_desc)) },
            trailingContent = { Switch(checked = failover, onCheckedChange = vm::setFailover) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_auto_update)) },
            supportingContent = { Text(stringResource(R.string.settings_auto_update_desc)) },
            trailingContent = { Switch(checked = autoUpdate, onCheckedChange = vm::setAutoUpdateSubscriptions) },
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
            headlineContent = { Text(stringResource(R.string.settings_battery)) },
            supportingContent = {
                Text(stringResource(if (unrestricted) R.string.settings_battery_on else R.string.settings_battery_off))
            },
            modifier = Modifier.clickable { openBatterySettings(context, unrestricted) },
        )
        HorizontalDivider()
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
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_updates)) },
            supportingContent = { Text(updateSummary(context, update)) },
            trailingContent = {
                if (UpdateChecker.available) {
                    TextButton(onClick = { vm.checkUpdates() }) { Text(stringResource(R.string.settings_updates_check)) }
                }
            },
        )
        update?.takeIf { it.status.pending.isNotEmpty() || it.unreadable.isNotEmpty() || it.status.upstream.isNotEmpty() }?.let { info ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                UpdateCard(info, inSecureSpace = true, onOpenPersonal = { vm.openPhoneApps(context) })
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_updates_notify)) },
            supportingContent = { Text(stringResource(R.string.settings_updates_notify_desc)) },
            trailingContent = {
                Switch(
                    checked = notifyUpdates,
                    onCheckedChange = {
                        notifyUpdates = it
                        UpdateChecker.setNotificationsOn(context, it)
                    },
                )
            },
        )
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_how_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_how_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun updateSummary(context: android.content.Context, info: UpdateChecker.Info?): String {
    if (!UpdateChecker.available) return context.getString(R.string.settings_updates_unavailable)
    if (info == null) return context.getString(R.string.settings_updates_never)
    val latest = info.status.latest
    val priority = info.priority
    val state = if (latest != null && priority != null) {
        context.getString(R.string.settings_updates_available, latest.name, UpdateChecker.priorityLabel(context, priority))
    } else {
        context.getString(R.string.settings_updates_current)
    }
    val checked = DateUtils.getRelativeTimeSpanString(info.checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    return state + "\n" + context.getString(R.string.settings_updates_checked, checked)
}

/**
 * Asks Android to stop limiting TunnelLock to save battery (some phones, Samsung
 * especially, otherwise put apps to sleep). If already allowed, opens the list.
 */
private fun openBatterySettings(context: android.content.Context, unrestricted: Boolean) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    runCatching { context.startActivity(if (unrestricted) list else request) }
        .onFailure { runCatching { context.startActivity(list) } }
}

private fun setLanguage(context: android.content.Context, tag: String) {
    if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
    }
}
