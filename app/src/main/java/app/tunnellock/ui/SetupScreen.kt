package app.tunnellock.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.CrossProfileApps
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tunnellock.R
import app.tunnellock.data.UpdateChecker
import app.tunnellock.strict.PhoneApp
import app.tunnellock.strict.ShareLinkActivity
import app.tunnellock.core.parser.LinkExtractor
import app.tunnellock.strict.PhoneApps
import app.tunnellock.strict.Policy
import app.tunnellock.tile.ToggleTileService
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
        // Updates are installed from here: check when opened (at most every 10 minutes).
        UpdateChecker.checkIfDue(context, notify = false, maxAgeMs = 10 * 60_000L)
    }
    val update by UpdateChecker.info.collectAsStateWithLifecycle()
    // "Work apps" paused: nothing in the secure space runs, TunnelLock included.
    val spacePaused = remember(reload) {
        runCatching { context.getSystemService(UserManager::class.java).isQuietModeEnabled(space) }.getOrDefault(false)
    }
    val sendFailed = stringResource(R.string.hub_send_failed)
    val tileAdded = stringResource(R.string.hub_tile_added)
    var tileOn by remember { mutableStateOf(ToggleTileService.isEnabled(context)) }
    var showLogs by remember { mutableStateOf(false) }
    if (showLogs) LogsDialog(onDismiss = { showLogs = false })

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // The personal side has its own log (the hand-over happens here).
                    TextButton(onClick = { showLogs = true }) { Text(stringResource(R.string.settings_logs)) }
                },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Button(
                    onClick = {
                        val chosen = apps.orEmpty().filter { it.packageName in selected }
                        when (val result = PhoneApps.send(context, space, chosen)) {
                            is PhoneApps.SendResult.Failed -> {
                                scope.launch { snackbar.showSnackbar(String.format(sendFailed, result.reason)) }
                            }
                            else -> {
                                selected = emptySet()
                            }
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
            if (spacePaused) {
                item { NoticeCard(text = stringResource(R.string.hub_space_paused), isError = true) }
            }
            update?.takeIf { it.status.pending.isNotEmpty() || it.status.upstream.isNotEmpty() }?.let { info ->
                item { UpdateCard(info, inSecureSpace = false) }
            }
            // Copies left outside have no protection: one wrong tap and the real IP is out.
            val outside = apps.orEmpty().filter { it.inSecureSpace && it.packageName != PhoneApps.PROBE_PACKAGE }
            if (outside.isNotEmpty()) {
                item { OutsideCopies(outside) }
            }
            item { MockLocationCard() }
            item { OpenLinkCard() }
            item {
                val setTile = { on: Boolean ->
                    ToggleTileService.setEnabled(context, on)
                    tileOn = on
                    if (on && Build.VERSION.SDK_INT >= 33) {
                        ToggleTileService.requestAdd(context) { added ->
                            if (added) scope.launch { snackbar.showSnackbar(tileAdded) }
                        }
                    }
                }
                ListItem(
                    modifier = Modifier.clickable { setTile(!tileOn) },
                    headlineContent = { Text(stringResource(R.string.hub_tile)) },
                    supportingContent = { Text(stringResource(R.string.hub_tile_desc)) },
                    trailingContent = { Switch(checked = tileOn, onCheckedChange = { setTile(it) }) },
                )
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
private fun OutsideCopies(apps: List<PhoneApp>) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onErrorContainer
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.hub_outside_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(stringResource(R.string.hub_outside_body), style = MaterialTheme.typography.bodySmall, color = color)
            apps.forEach { app ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppIcon(app.info)
                    Text(
                        app.label,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = color,
                    )
                    TextButton(onClick = { removeOutsideCopy(context, app) }) {
                        Text(stringResource(if (app.system) R.string.hub_outside_disable else R.string.hub_outside_remove))
                    }
                }
            }
        }
    }
}

/**
 * Uninstalls the personal copy only (the copy in the secure space is another
 * user's and stays). Preinstalled apps cannot be uninstalled: their settings
 * page offers "Disable".
 */
@Composable
private fun MockLocationCard() {
    val context = LocalContext.current
    val store = (context.applicationContext as app.tunnellock.TunnelLockApp).store
    val current by store.mockLocation.collectAsStateWithLifecycle()
    val status by app.tunnellock.vpn.MockLocationState.status.collectAsStateWithLifecycle()
    // Start from the last point so re-enabling after a turn-off needs no re-entry.
    var text by remember { mutableStateOf(current.ifBlank { store.lastMockLocation }) }
    val parsed = app.tunnellock.core.geo.CoordinateParser.parse(text)
    val on = current.isNotBlank()
    val saved by store.savedLocations.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var showMap by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    if (showMap) {
        MapPickerDialog(
            initial = parsed,
            onCancel = { showMap = false },
            onConfirm = { lat, lng ->
                text = String.format(java.util.Locale.US, "%.6f, %.6f", lat, lng)
                showMap = false
            },
        )
    }
    if (showSave && parsed != null) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text(stringResource(R.string.mock_save)) },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.mock_save_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = saveName.isNotBlank(),
                    onClick = {
                        store.saveLocation(saveName, parsed.latitude, parsed.longitude)
                        saveName = ""
                        showSave = false
                    },
                ) { Text(stringResource(R.string.mock_save)) }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text(stringResource(R.string.mock_map_cancel)) } },
        )
    }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.hub_mock_title))
        Text(
            stringResource(R.string.hub_mock_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            isError = text.isNotBlank() && parsed == null,
            label = { Text(stringResource(R.string.mock_coordinates)) },
            placeholder = { Text("35.6892, 51.3890") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showMap = true }) { Text(stringResource(R.string.mock_pick_map)) }
            OutlinedButton(enabled = parsed != null, onClick = { saveName = ""; showSave = true }) {
                Text(stringResource(R.string.mock_save))
            }
        }
        parsed?.let { Text(stringResource(R.string.mock_parsed, it.format()), style = MaterialTheme.typography.bodySmall) }
        if (parsed == null && text.isNotBlank()) {
            val why = if (app.tunnellock.core.geo.CoordinateParser.looksLikeUrl(text)) R.string.mock_link_no_coords else R.string.mock_bad_coords
            Text(stringResource(why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (saved.isNotEmpty()) {
            Text(stringResource(R.string.mock_saved_title), style = MaterialTheme.typography.labelMedium)
            saved.forEach { loc ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { text = String.format(java.util.Locale.US, "%.6f, %.6f", loc.lat, loc.lng) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "${loc.name}  (${String.format(java.util.Locale.US, "%.4f, %.4f", loc.lat, loc.lng)})",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    IconButton(onClick = { store.removeSavedLocation(loc.name) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.mock_map_cancel))
                    }
                }
            }
        }
        if (on && status == app.tunnellock.vpn.MockStatus.NEEDS_DEVELOPER_OPTION) {
            Text(stringResource(R.string.mock_needs_dev), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } }
            }) { Text(stringResource(R.string.mock_open_dev)) }
        }
        if (on && status == app.tunnellock.vpn.MockStatus.ACTIVE) {
            Text(stringResource(R.string.mock_active), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                enabled = parsed != null,
                onClick = {
                    if (Build.VERSION.SDK_INT >= 23) permission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    store.setMockLocation(parsed!!.format())
                    app.tunnellock.vpn.MockLocationService.start(context)
                },
            ) { Text(stringResource(if (on) R.string.hub_mock_update else R.string.hub_mock_start)) }
            if (on) {
                OutlinedButton(onClick = {
                    store.setMockLocation("")
                    app.tunnellock.vpn.MockLocationService.stop(context)
                }) { Text(stringResource(R.string.hub_mock_stop)) }
            }
        }
    }
}

/**
 * A full-screen OpenStreetMap picker for the fake-location point. The map pans and zooms; a fixed
 * crosshair marks the center, and "Use this point" returns the center's coordinates. No Google
 * dependency and no API key -- tiles come from OpenStreetMap. Only the personal copy opens this
 * (the device-wide fake is set there).
 */
@Composable
private fun MapPickerDialog(
    initial: app.tunnellock.core.geo.Coordinates?,
    onCancel: () -> Unit,
    onConfirm: (Double, Double) -> Unit,
) {
    val context = LocalContext.current
    val mapView = remember {
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = context.packageName
            val base = java.io.File(context.cacheDir, "osmdroid")
            osmdroidBasePath = base
            osmdroidTileCache = java.io.File(base, "tiles")
        }
        org.osmdroid.views.MapView(context).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(if (initial != null) 15.0 else 5.0)
            controller.setCenter(
                org.osmdroid.util.GeoPoint(initial?.latitude ?: 35.6892, initial?.longitude ?: 51.389),
            )
            // Required attribution for OpenStreetMap tiles ("© OpenStreetMap contributors").
            overlays.add(org.osmdroid.views.overlay.CopyrightOverlay(context))
        }
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            val cross = MaterialTheme.colorScheme.primary
            Canvas(Modifier.align(Alignment.Center).size(48.dp)) {
                val w = size.width
                val h = size.height
                drawCircle(cross, radius = w / 3f, style = Stroke(width = 3.dp.toPx()))
                drawLine(cross, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 2.dp.toPx())
                drawLine(cross, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 2.dp.toPx())
            }
            // Zoom buttons zoom around the centre, so the crosshair stays on the chosen point.
            Column(
                Modifier.align(Alignment.CenterEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { mapView.controller.zoomIn() },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(44.dp),
                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                FilledTonalButton(
                    onClick = { mapView.controller.zoomOut() },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(44.dp),
                ) { Text("−", style = MaterialTheme.typography.titleLarge) }
            }
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val runSearch = {
                    if (query.isNotBlank() && !searching) {
                        searching = true
                        notFound = false
                        scope.launch {
                            val gp = geocode(context, query.trim())
                            if (gp != null) {
                                // Land at street level, centred exactly on the result (no
                                // animation, so the crosshair stays on the point).
                                mapView.controller.setZoom(16.0)
                                mapView.controller.setCenter(gp)
                            } else {
                                notFound = true
                            }
                            searching = false
                        }
                        Unit
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; notFound = false },
                    singleLine = true,
                    label = { Text(stringResource(R.string.mock_map_search)) },
                    trailingIcon = {
                        IconButton(onClick = { runSearch() }) {
                            if (searching) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.mock_map_search))
                            }
                        }
                    },
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { runSearch() }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
                )
                Text(
                    if (notFound) stringResource(R.string.mock_map_search_none) else stringResource(R.string.mock_map_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notFound) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(6.dp),
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.mock_map_cancel))
                }
                Button(
                    onClick = {
                        val c = mapView.mapCenter
                        onConfirm(c.latitude, c.longitude)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.mock_map_confirm)) }
            }
        }
    }
}

/**
 * Finds a place by name for the map search. Tries Android's own geocoder first (its OS backend
 * when present), then falls back to OpenStreetMap's Nominatim so search works without Google
 * services. Runs off the main thread. Returns null when nothing is found.
 */
private suspend fun geocode(context: android.content.Context, query: String): org.osmdroid.util.GeoPoint? =
    withContext(Dispatchers.IO) {
        runCatching {
            if (android.location.Geocoder.isPresent()) {
                @Suppress("DEPRECATION")
                val hit = android.location.Geocoder(context).getFromLocationName(query, 1)?.firstOrNull()
                if (hit != null) return@withContext org.osmdroid.util.GeoPoint(hit.latitude, hit.longitude)
            }
        }
        runCatching {
            val url = java.net.URL(
                "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                    java.net.URLEncoder.encode(query, "UTF-8"),
            )
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("User-Agent", context.packageName)
                connectTimeout = 8000
                readTimeout = 8000
            }
            conn.inputStream.bufferedReader().use { reader ->
                val arr = org.json.JSONArray(reader.readText())
                if (arr.length() > 0) {
                    val o = arr.getJSONObject(0)
                    return@withContext org.osmdroid.util.GeoPoint(
                        o.getString("lat").toDouble(),
                        o.getString("lon").toDouble(),
                    )
                }
            }
        }
        null
    }

@Composable
private fun OpenLinkCard() {
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.hub_open_link_title))
        Text(
            stringResource(R.string.hub_open_link_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            singleLine = true,
            label = { Text(stringResource(R.string.hub_open_link_field)) },
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = {
                val url = LinkExtractor.firstUrl(link)
                val activity = context as? android.app.Activity
                if (url != null && activity != null) {
                    ShareLinkActivity.open(activity, url)
                    link = ""
                }
            },
            enabled = LinkExtractor.firstUrl(link) != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.hub_open_link_button)) }
    }
}

private fun removeOutsideCopy(context: Context, app: PhoneApp) {
    val uri = Uri.parse("package:${app.packageName}")
    val intent = if (app.system) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri) else Intent(Intent.ACTION_DELETE, uri)
    runCatching { context.startActivity(intent) }
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
