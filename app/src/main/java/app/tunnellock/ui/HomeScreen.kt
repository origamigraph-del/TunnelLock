package app.tunnellock.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunnellock.R
import app.tunnellock.strict.Policy
import app.tunnellock.ui.theme.phaseColor
import app.tunnellock.vpn.Phase
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun HomeScreen(vm: MainViewModel, onOpenServers: () -> Unit, onOpenApps: () -> Unit) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val servers by vm.store.servers.collectAsStateWithLifecycle()
    val selectedId by vm.store.selectedId.collectAsStateWithLifecycle()
    val selected = servers.find { it.id == selectedId }

    var lockdown by remember { mutableStateOf(true) }
    var protectedCount by remember { mutableIntStateOf(0) }
    var needsVpnPermission by remember { mutableStateOf(false) }
    var needsNotificationPermission by remember { mutableStateOf(false) }

    fun refresh() {
        lockdown = Policy.isLockdownActive(context)
        protectedCount = Policy.protectedPackages(context).size
        needsVpnPermission = VpnService.prepare(context) != null
        needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh()
        vm.ensureGuard()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.size(8.dp))
        ConnectButton(
            phase = status.phase,
            enabled = selected != null || status.phase.isActive,
            onClick = vm::toggleConnection,
        )
        Text(
            stringResource(status.phase.title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = phaseColor(status.phase),
        )
        if (status.phase == Phase.CONNECTED && status.connectedSince != null) {
            Uptime(status.connectedSince!!)
        }
        Text(
            stringResource(status.phase.description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        status.error?.let { error ->
            NoticeCard(text = error, isError = true)
        }
        if (needsVpnPermission) {
            NoticeCard(
                text = stringResource(R.string.home_vpn_permission),
                isError = true,
                action = stringResource(R.string.home_allow),
                onAction = { VpnService.prepare(context)?.let { vpnPermission.launch(it) } },
            )
        }
        if (needsNotificationPermission) {
            NoticeCard(
                text = stringResource(R.string.home_notifications),
                action = stringResource(R.string.home_allow),
                onAction = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }

        InfoCard(
            icon = Icons.AutoMirrored.Filled.List,
            title = stringResource(R.string.home_server),
            value = selected?.name ?: stringResource(R.string.home_no_server),
            detail = selected?.let { ltr("${it.protocol} · ${it.address}") },
            onClick = onOpenServers,
        )
        InfoCard(
            icon = Icons.Filled.Lock,
            title = stringResource(R.string.home_protected_apps),
            value = stringResource(R.string.home_protected_count, protectedCount),
            detail = if (status.appsPaused) stringResource(R.string.home_apps_paused) else stringResource(R.string.home_apps_running),
            onClick = onOpenApps,
        )
        InfoCard(
            icon = if (lockdown) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            title = stringResource(R.string.home_killswitch),
            value = stringResource(if (lockdown) R.string.home_killswitch_on else R.string.home_killswitch_off),
            detail = if (lockdown) null else stringResource(R.string.home_fix),
            onClick = if (lockdown) null else ({
                vm.reapplyPolicies()
                refresh()
            }),
        )
    }
}

@Composable
private fun ConnectButton(phase: Phase, enabled: Boolean, onClick: () -> Unit) {
    val color = phaseColor(phase)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = color.copy(alpha = 0.12f),
            border = BorderStroke(4.dp, color),
            modifier = Modifier.size(176.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (phase == Phase.CONNECTING || phase == Phase.RECONNECTING) {
                    CircularProgressIndicator(modifier = Modifier.size(64.dp), color = color, strokeWidth = 5.dp)
                } else {
                    Icon(
                        if (phase == Phase.CONNECTED) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(72.dp),
                    )
                }
            }
        }
        Text(
            stringResource(if (phase.isActive) R.string.home_tap_disconnect else R.string.home_tap_connect),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Uptime(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((now - since) / 1000).coerceAtLeast(0)
    Text(
        String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    value: String,
    detail: String?,
    onClick: (() -> Unit)?,
) {
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
fun NoticeCard(text: String, isError: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
    val colors = if (isError) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null && onAction != null) {
                FilledTonalButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

/** Keeps addresses like "VLESS · example.com:443" left-to-right inside Persian text. */
fun ltr(text: String) = "⁦$text⁩"
