package app.tunnellock.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunnellock.R
import app.tunnellock.strict.AppCloner
import app.tunnellock.strict.AppItem
import app.tunnellock.strict.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val apps by vm.apps.collectAsStateWithLifecycle()
    val installing by vm.installing.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var canInstall by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }

    LifecycleResumeEffect(Unit) {
        canInstall = context.packageManager.canRequestPackageInstalls()
        vm.refreshApps()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.apps_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!canInstall) {
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.apps_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            AppList(vm, list, query, installing, status.appsPaused, canInstall)
        }
    }
}

@Composable
private fun AppList(
    vm: MainViewModel,
    list: List<AppItem>,
    query: String,
    installing: Set<String>,
    appsPaused: Boolean,
    canInstall: Boolean,
) {
    val context = LocalContext.current
    val filtered = list.filter {
        query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }
    val inside = filtered.filter { it.installedHere }
    val outside = filtered.filterNot { it.installedHere }

    LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        item { Box(Modifier.padding(horizontal = 8.dp)) { SectionTitle(stringResource(R.string.apps_protected, inside.size)) } }
        if (inside.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.apps_protected_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        items(inside, key = { "in-" + it.packageName }) { app ->
            val paused = remember(app.packageName, appsPaused, list) { Policy.isSuspended(context, app.packageName) }
            AppRow(
                app = app,
                subtitle = stringResource(if (paused) R.string.apps_paused else R.string.apps_running),
            ) {
                TextButton(onClick = { context.startActivity(AppCloner.uninstallIntent(app.packageName)) }) {
                    Text(stringResource(R.string.apps_remove))
                }
            }
        }
        item { Box(Modifier.padding(horizontal = 8.dp)) { SectionTitle(stringResource(R.string.apps_available, outside.size)) } }
        items(outside, key = { "out-" + it.packageName }) { app ->
            AppRow(app = app, subtitle = null) {
                if (app.packageName in installing) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    FilledTonalButton(onClick = { vm.addApp(app) }, enabled = canInstall) {
                        Text(stringResource(R.string.apps_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppItem, subtitle: String?, trailing: @Composable () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { app.info.loadIcon(context.packageManager).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    ListItem(
        leadingContent = {
            val bitmap = icon
            if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp)) else Box(Modifier.size(40.dp))
        },
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = trailing,
    )
}
