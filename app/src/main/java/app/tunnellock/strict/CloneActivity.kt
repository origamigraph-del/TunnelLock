package app.tunnellock.strict

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.tunnellock.R
import app.tunnellock.data.AppLog
import app.tunnellock.ui.NoticeCard
import app.tunnellock.ui.theme.TunnelLockTheme
import kotlinx.coroutines.launch

/**
 * Inside the secure space: receives the apps picked in the personal profile and
 * adds them one by one. It stays on screen so it may start Android's install
 * confirmations.
 */
class CloneActivity : ComponentActivity() {

    companion object {
        const val ACTION_CLONE_APPS = "app.tunnellock.action.CLONE_APPS"
        private const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_PATHS = "paths"
        private const val EXTRA_SYSTEM = "system"

        /** The hand-over intent sent from the personal profile; Android forwards it into the secure space. */
        fun handOverIntent(targets: List<CloneTarget>): Intent =
            Intent(ACTION_CLONE_APPS)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(EXTRA_PACKAGES, targets.map { it.packageName }.toTypedArray())
                .putExtra(EXTRA_LABELS, targets.map { it.label }.toTypedArray())
                .putExtra(EXTRA_PATHS, targets.map { it.paths.joinToString("\n") }.toTypedArray())
                .putExtra(EXTRA_SYSTEM, targets.map { it.system }.toBooleanArray())

        private fun readTargets(intent: Intent): List<CloneTarget> {
            val packages = intent.getStringArrayExtra(EXTRA_PACKAGES) ?: return emptyList()
            val labels = intent.getStringArrayExtra(EXTRA_LABELS) ?: return emptyList()
            val paths = intent.getStringArrayExtra(EXTRA_PATHS) ?: return emptyList()
            val system = intent.getBooleanArrayExtra(EXTRA_SYSTEM) ?: return emptyList()
            if (setOf(labels.size, paths.size, system.size) != setOf(packages.size)) return emptyList()
            return packages.indices.map { i ->
                CloneTarget(packages[i], labels[i], paths[i].split('\n').filter { it.isNotEmpty() }, system[i])
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!Policy.isProfileOwner(this)) {
            finish()
            return
        }
        receive(intent)
        // Show install confirmations only while this screen is in front.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                CloneQueue.confirm.collect { pending ->
                    if (pending != null) {
                        CloneQueue.takeConfirm()?.let { runCatching { startActivity(it) } }
                    }
                }
            }
        }
        setContent {
            TunnelLockTheme {
                CloneScreen(onDone = {
                    CloneQueue.clearFinished()
                    finish()
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    override fun onResume() {
        super.onResume()
        startIfAllowed()
    }

    private fun receive(intent: Intent) {
        val targets = readTargets(intent)
        if (targets.isEmpty()) return
        AppLog.i("Clone", "received ${targets.size} apps from the personal profile")
        CloneQueue.add(targets)
        startIfAllowed()
    }

    private fun startIfAllowed() {
        val needsInstaller = CloneQueue.items.value.any { it.status == CloneStatus.WAITING && !it.target.system }
        if (needsInstaller && !packageManager.canRequestPackageInstalls()) return
        CloneQueue.processNext(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloneScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val items by CloneQueue.items.collectAsStateWithLifecycle()
    var canInstall by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    LifecycleResumeEffect(Unit) {
        canInstall = context.packageManager.canRequestPackageInstalls()
        onPauseOrDispose { }
    }
    val needsInstaller = items.any { it.status == CloneStatus.WAITING && !it.target.system }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.clone_title)) }) },
        bottomBar = {
            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) { Text(stringResource(R.string.clone_done)) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.clone_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (needsInstaller && !canInstall) {
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
            items(items, key = { it.target.packageName }) { item ->
                ListItem(
                    headlineContent = { Text(item.target.label) },
                    supportingContent = {
                        Text(
                            when (item.status) {
                                CloneStatus.WAITING -> stringResource(R.string.clone_waiting)
                                CloneStatus.WORKING -> stringResource(R.string.clone_working)
                                CloneStatus.CONFIRM -> stringResource(R.string.clone_confirm)
                                CloneStatus.DONE -> stringResource(R.string.clone_done_item)
                                CloneStatus.FAILED -> stringResource(R.string.clone_failed, item.message.orEmpty())
                            },
                        )
                    },
                    trailingContent = {
                        when (item.status) {
                            CloneStatus.DONE -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            CloneStatus.FAILED -> Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                            CloneStatus.WAITING -> Unit
                            else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    },
                )
            }
        }
    }
}
