package app.tunnellock.probe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Every probe request must open a new connection.
        System.setProperty("http.keepAlive", "false")
        ProbeState.createChannels(this)
        ProbeState.load(this)
        ProbeState.flushOutbox(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                ProbeScreen()
            }
        }
    }
}

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Amber = Color(0xFFEF8F00)
private val Grey = Color(0xFF546E7A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProbeScreen() {
    val context = LocalContext.current
    val running by ProbeState.running.collectAsStateWithLifecycle()
    val last by ProbeState.last.collectAsStateWithLifecycle()
    val counters by ProbeState.counters.collectAsStateWithLifecycle()
    val lines by ProbeState.lines.collectAsStateWithLifecycle()
    var interval by remember { mutableIntStateOf(ProbeState.intervalSeconds(context)) }
    var home by remember { mutableStateOf(ProbeState.homeCountry(context)) }
    var realIp by remember { mutableStateOf(ProbeState.realIp(context)) }
    var autoSend by remember { mutableStateOf(ProbeState.autoSend(context)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ProbeService.start(context)
    }
    val inWorkProfile = ProbeState.inSecureSpace(context)

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                if (inWorkProfile) {
                    Text(stringResource(R.string.profile_work), style = MaterialTheme.typography.labelLarge, color = Green)
                } else {
                    // The most common mistake: testing with the unprotected copy.
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.18f)),
                    ) {
                        Text(
                            stringResource(R.string.profile_personal),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            item { StatusCard(last, running) }
            item {
                Text(
                    stringResource(R.string.counters, counters.online, counters.blocked, counters.leaks, counters.ipChanges, counters.direct),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        Button(onClick = { ProbeService.stop(context) }) { Text(stringResource(R.string.action_stop)) }
                    } else {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                ProbeService.start(context)
                            }
                        }) { Text(stringResource(R.string.action_start)) }
                    }
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("TunnelProbe log", ProbeState.text()))
                    }) { Text(stringResource(R.string.action_copy)) }
                    OutlinedButton(onClick = { ProbeState.clear(context) }) { Text(stringResource(R.string.action_clear)) }
                }
            }
            if (ProbeReport.available) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ProbeState.sendInBackground(context, ProbeState.text()) }) {
                            Text(stringResource(R.string.action_send))
                        }
                        Switch(
                            checked = autoSend,
                            onCheckedChange = {
                                autoSend = it
                                ProbeState.setAutoSend(context, it)
                            },
                        )
                        Text(stringResource(R.string.auto_send), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.interval), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 3, 5, 10).forEach { seconds ->
                            FilterChip(
                                selected = interval == seconds,
                                onClick = {
                                    interval = seconds
                                    ProbeState.setIntervalSeconds(context, seconds)
                                },
                                label = { Text("${seconds}s") },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = home,
                            onValueChange = {
                                home = it.take(2).uppercase()
                                ProbeState.setHomeCountry(context, home)
                            },
                            singleLine = true,
                            label = { Text(stringResource(R.string.home_country)) },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = realIp,
                            onValueChange = {
                                realIp = it.trim()
                                ProbeState.setRealIp(context, realIp)
                            },
                            singleLine = true,
                            label = { Text(stringResource(R.string.real_ip)) },
                            modifier = Modifier.weight(2f),
                        )
                    }
                    OutlinedButton(onClick = { ProbeState.resetBaseline(context) }) {
                        Text(stringResource(R.string.action_reset_baseline))
                    }
                }
            }
            item {
                Card {
                    Text(stringResource(R.string.how_to), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Text(stringResource(R.string.log_title), style = MaterialTheme.typography.titleSmall) }
            // The log is technical English: keep it left-to-right.
            items(lines.takeLast(300).reversed()) { line ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = when {
                            "LEAK" in line -> Red
                            "NEW-IP" in line || "DIRECT" in line -> Amber
                            "BLOCKED" in line -> Grey
                            line.contains(" OK ") -> Green
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(last: LastRound?, running: Boolean) {
    val (title, color) = when (last?.verdict) {
        Verdict.ONLINE -> stringResource(R.string.verdict_online) to Green
        Verdict.BLOCKED -> stringResource(R.string.verdict_blocked) to Grey
        Verdict.LEAK -> stringResource(R.string.verdict_leak) to Red
        Verdict.IP_CHANGED -> stringResource(R.string.verdict_ip_changed) to Amber
        Verdict.DIRECT -> stringResource(R.string.verdict_direct) to Amber
        null -> stringResource(if (running) R.string.status_starting else R.string.verdict_idle) to Grey
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = color, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            last?.let {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(it.summary, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
