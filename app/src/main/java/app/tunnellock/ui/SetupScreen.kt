package app.tunnellock.ui

import android.app.Activity
import android.content.ComponentName
import android.content.pm.CrossProfileApps
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.tunnellock.R
import app.tunnellock.strict.Policy

/** Shown in the personal profile: creates the secure space, then opens TunnelLock inside it. */
@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val crossProfile = remember { context.getSystemService(CrossProfileApps::class.java) }
    var secureSpaces by remember { mutableStateOf(crossProfile.targetUserProfiles) }
    var canCreate by remember { mutableStateOf(Policy.canCreateProfile(context)) }
    var waiting by remember { mutableStateOf(false) }
    var launchError by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        secureSpaces = crossProfile.targetUserProfiles
        canCreate = Policy.canCreateProfile(context)
        if (secureSpaces.isNotEmpty()) waiting = false
        onPauseOrDispose { }
    }

    val provision = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        secureSpaces = crossProfile.targetUserProfiles
        waiting = result.resultCode == Activity.RESULT_OK && secureSpaces.isEmpty()
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
                secureSpaces.isNotEmpty() -> Button(
                    onClick = {
                        crossProfile.startMainActivity(ComponentName(context, MainActivity::class.java), secureSpaces.first())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.setup_open)) }

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
