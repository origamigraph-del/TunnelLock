package app.tunnellock.ui

import android.app.Activity
import android.content.ComponentName
import android.content.pm.CrossProfileApps
import android.os.UserHandle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.tunnellock.R
import app.tunnellock.strict.PhoneApp
import app.tunnellock.strict.PhoneApps
import app.tunnellock.strict.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown in the personal profile. Before the secure space exists it creates it;
 * afterwards it opens it and is where apps are picked to be added to it (only
 * the personal side can see the phone's apps).
 */
@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val crossProfile = remember { context.getSystemService(CrossProfileApps::class.java) }
    var secureSpaces by remember { mutableStateOf(crossProfile.targetUserProfiles) }

    LifecycleResumeEffect(Unit) {
        secureSpaces = crossProfile.targetUserProfiles
        onPauseOrDispose { }
    }

    val space = secureSpaces.firstOrNull()
    if (space == null) {
        CreateSecureSpace(onCreated = { secureSpaces = crossProfile.targetUserProfiles })
    } else {
        PersonalHub(space)
    }
}

@Composable
private fun CreateSecureSpace(onCreated: () -> Unit) {
    val context = LocalContext.current
    var canCreate by remember { mutableStateOf(Policy.canCreateProfile(context)) }
    var waiting by remember { mutableStateOf(false) }
    var launchError by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        canCreate = Policy.canCreateProfile(context)
        onPauseOrDispose { }
    }

    val provision = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onCreated()
        waiting = result.resultCode == Activity.RESULT_OK
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.setup_steps_title), style = MaterialTheme.typography.titleMedium)
                    listOf(
                        R.string.setup_step1,
                        R.string.setup_step2,
                        R.string.setup_step3,
                        R.string.setup_step4,
                        R.string.setup_step5,
                    ).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                }
            }

            when {
                waiting -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.setup_waiting), textAlign = TextAlign.Center)
                }

                canCreate -> Button(
                    onClick = {
                        launchError = false
                        try {
                            provision.launch(Policy.provisioningIntent(context))
                        } catch (e: Exception) {
                            launchError = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.setup_create)) }

                else -> ErrorNote(stringResource(R.string.setup_cannot_create))
            }
            if (launchError) ErrorNote(stringResource(R.string.setup_provisioning_missing))

            Text(
                stringResource(R.string.setup_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalHub(space: UserHandle) {
    val context = LocalContext.current
    val crossProfile = remember { context.getSystemService(CrossProfileApps::class.java) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<PhoneApp>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    LifecycleResumeEffect(Unit) {
        reload++
        onPauseOrDispose { }
    }
    LaunchedEffect(reload) {
        apps = withContext(Dispatchers.IO) { PhoneApps.list(context) }
    }
    val sendFailed = stringResource(R.string.hub_send_failed)

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Button(
                    onClick = {
                        val chosen = apps.orEmpty().filter { it.packageName in selected }
                        if (PhoneApps.send(context, chosen)) {
                            selected = emptySet()
                        } else {
                            scope.launch { snackbar.showSnackbar(sendFailed) }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) { Text(stringResource(R.string.hub_add_selected, selected.size)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.hub_intro), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(
                            onClick = {
                                crossProfile.startMainActivity(ComponentName(context, MainActivity::class.java), space)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.setup_open)) }
                    }
                }
            }
            item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.hub_apps_title))
                    Text(
                        stringResource(R.string.hub_apps_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.apps_search)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val list = apps
            if (list == null) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            } else {
                val filtered = list.filter {
                    query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
                }
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    val toggle = {
                        selected = if (checked) selected - app.packageName else selected + app.packageName
                    }
                    val note = when {
                        app.inSecureSpace -> stringResource(R.string.hub_in_space)
                        app.system -> stringResource(R.string.hub_system_app)
                        else -> null
                    }
                    ListItem(
                        modifier = Modifier.clickable(enabled = !app.inSecureSpace, onClick = toggle),
                        leadingContent = { AppIcon(app.info) },
                        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = note?.let { { Text(it) } },
                        trailingContent = {
                            Checkbox(
                                checked = checked || app.inSecureSpace,
                                onCheckedChange = { toggle() },
                                enabled = !app.inSecureSpace,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorNote(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
