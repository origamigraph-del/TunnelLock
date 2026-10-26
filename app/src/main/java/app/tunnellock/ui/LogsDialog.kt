package app.tunnellock.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.tunnellock.R
import app.tunnellock.data.AppLog
import app.tunnellock.data.LogSender
import kotlinx.coroutines.launch

/** Shows this app's log, with "send to developer" and "copy". */
@Composable
fun LogsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines by AppLog.lines.collectAsStateWithLifecycle()
    var sending by remember { mutableStateOf(false) }
    val sent = stringResource(R.string.logs_sent)
    val failed = stringResource(R.string.logs_send_failed)

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
            Row {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("TunnelLock log", AppLog.text()))
                    onDismiss()
                }) { Text(stringResource(R.string.logs_copy)) }
                if (LogSender.available) {
                    TextButton(
                        enabled = !sending,
                        onClick = {
                            sending = true
                            scope.launch {
                                val result = LogSender.send(context)
                                sending = false
                                val message = result.fold({ sent }, { String.format(failed, it.message.orEmpty()) })
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                if (result.isSuccess) onDismiss()
                            }
                        },
                    ) { Text(stringResource(if (sending) R.string.logs_sending else R.string.logs_send)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.logs_close)) } },
    )
}
