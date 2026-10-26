package app.tunnellock.strict

import android.content.Context
import app.tunnellock.vpn.Phase
import app.tunnellock.vpn.TunnelState

/**
 * Pauses and un-pauses the protected apps. Every decision runs under one lock
 * together with the phase change that causes it, so a "pause because we are not
 * connected" can never land after the tunnel has just connected.
 *
 * Network blocking does not depend on this: the TUN and Android's lockdown cut
 * the internet on their own. Pausing only decides whether the apps can be opened.
 */
object AppGate {
    private val lock = Any()

    /** Runs [update] (the phase change to CONNECTED) and lets the apps run. */
    fun open(context: Context, update: () -> Unit) = synchronized(lock) {
        update()
        Policy.setAllSuspended(context, false)
    }

    /** Runs [update] and pauses all protected apps. */
    fun closeAll(context: Context, update: () -> Unit) = synchronized(lock) {
        update()
        Policy.setAllSuspended(context, true)
    }

    /**
     * Lets [pkg] be opened while disconnected (or not). The change applies right
     * away: the app is un-paused, or paused again if the other apps are paused now.
     */
    fun setNeverPause(context: Context, pkg: String, value: Boolean) = synchronized(lock) {
        Policy.setNeverPause(context, pkg, value)
        val status = TunnelState.status.value
        when {
            value -> Policy.setSuspended(context, listOf(pkg), false)
            status.appsPaused && status.phase != Phase.CONNECTED -> Policy.setSuspended(context, listOf(pkg), true)
        }
    }

    /** Pauses [packages] (all protected apps if null) unless the tunnel is connected right now. */
    fun closeIfDisconnected(context: Context, packages: List<String>? = null) = synchronized(lock) {
        if (TunnelState.status.value.phase == Phase.CONNECTED) return@synchronized
        if (packages == null) Policy.setAllSuspended(context, true) else Policy.setSuspended(context, packages, true)
        TunnelState.update { it.copy(appsPaused = true) }
    }
}
