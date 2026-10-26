package app.tunnellock.vpn

import androidx.annotation.StringRes
import app.tunnellock.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase(@StringRes val title: Int, @StringRes val description: Int) {
    /** VPN service not running. In the secure space Android's lockdown still blocks the apps. */
    OFF(R.string.phase_off, R.string.phase_off_desc),

    /** Tunnel interface up, no server: protected apps are paused and have no internet. */
    GUARDING(R.string.phase_guarding, R.string.phase_guarding_desc),
    CONNECTING(R.string.phase_connecting, R.string.phase_connecting_desc),

    /** The only phase in which protected apps run and reach the internet. */
    CONNECTED(R.string.phase_connected, R.string.phase_connected_desc),

    /** Connection lost: traffic is dropped inside the tunnel while we reconnect. */
    RECONNECTING(R.string.phase_reconnecting, R.string.phase_reconnecting_desc),
    ;

    val isActive: Boolean get() = this == CONNECTING || this == CONNECTED || this == RECONNECTING
}

data class TunnelStatus(
    val phase: Phase = Phase.OFF,
    val serverName: String? = null,
    val connectedSince: Long? = null,
    val error: String? = null,
    val appsPaused: Boolean = true,
)

object TunnelState {
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status

    fun update(transform: (TunnelStatus) -> TunnelStatus) = _status.update(transform)
}
