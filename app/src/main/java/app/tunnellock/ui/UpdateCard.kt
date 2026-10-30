package app.tunnellock.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tunnellock.R
import app.tunnellock.core.update.UpdatePriority
import app.tunnellock.data.UpdateChecker

/**
 * A newer version and how urgent it is. In the secure space it sends the user to the
 * outer TunnelLock: an APK installed on the normal side updates both copies.
 */
@Composable
fun UpdateCard(info: UpdateChecker.Info, inSecureSpace: Boolean, onOpenPersonal: () -> Unit = {}) {
    val context = LocalContext.current
    val latest = info.status.latest
    val priority = info.priority
    val urgent = priority != null && priority >= UpdatePriority.IMPORTANT
    val colors = if (urgent) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (latest != null && priority != null) {
                Text(
                    stringResource(R.string.update_title, latest.name, UpdateChecker.priorityLabel(context, priority)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                UpdateChecker.notes(context, latest).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                val more = info.status.pending.size - 1
                if (more > 0) Text(stringResource(R.string.update_more_versions, more), style = MaterialTheme.typography.bodySmall)
            }
            if (info.unreadable.isNotEmpty()) {
                Text(UpdateChecker.unreadableText(context, info.unreadable), style = MaterialTheme.typography.bodyMedium)
                if (latest == null) Text(stringResource(R.string.update_no_version_yet), style = MaterialTheme.typography.bodySmall)
            }
            info.status.upstream.forEach { Text(UpdateChecker.upstreamText(context, it), style = MaterialTheme.typography.bodySmall) }
            if (latest != null) {
                if (inSecureSpace) {
                    FilledTonalButton(onClick = onOpenPersonal) { Text(stringResource(R.string.update_download_personal)) }
                } else {
                    FilledTonalButton(onClick = { open(context, UpdateChecker.downloadUrl("TunnelLock.apk")) }) {
                        Text(stringResource(R.string.update_download))
                    }
                    TextButton(onClick = { open(context, UpdateChecker.downloadUrl("TunnelProbe.apk")) }) {
                        Text(stringResource(R.string.update_download_probe))
                    }
                    Text(stringResource(R.string.update_install_hint), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.update_priority_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun open(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
