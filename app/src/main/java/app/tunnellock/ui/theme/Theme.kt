package app.tunnellock.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.tunnellock.vpn.Phase

private val Teal = Color(0xFF00695C)

@Composable
fun TunnelLockTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF4DB6AC))
        else -> lightColorScheme(primary = Teal)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun phaseColor(phase: Phase): Color = when (phase) {
    Phase.CONNECTED -> Color(0xFF2E7D32)
    Phase.RECONNECTING -> Color(0xFFEF8F00)
    Phase.CONNECTING -> MaterialTheme.colorScheme.primary
    Phase.GUARDING -> Color(0xFF546E7A)
    Phase.OFF -> MaterialTheme.colorScheme.error
}
