package app.tunnellock.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppIcon(info: ApplicationInfo) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, info.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { info.loadIcon(context.packageManager).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Box(Modifier.size(40.dp))
    }
}
