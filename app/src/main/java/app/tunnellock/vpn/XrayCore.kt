package app.tunnellock.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.os.Build
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import app.tunnellock.data.AppLog
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import libv2ray.ProcessFinder
import java.net.InetSocketAddress

/** Thin wrapper around the Xray core (AndroidLibXrayLite / gomobile). */
object XrayCore {
    private const val TAG = "XrayCore"

    private var controller: CoreController? = null

    @Synchronized
    private fun controller(context: Context): CoreController {
        controller?.let { return it }
        Seq.setContext(context.applicationContext)
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
        return Libv2ray.newCoreController(Callback).also {
            // Lets routing rules match connections by app ("process" = the app's UID).
            if (Build.VERSION.SDK_INT >= 29) it.registerProcessFinder(AppFinder(context.applicationContext))
            controller = it
        }
    }

    /** Starts Xray reading packets from [tunFd]. Xray works on its own duplicate of the descriptor. */
    @Synchronized
    fun start(context: Context, config: String, tunFd: Int) {
        val c = controller(context)
        if (c.isRunning) c.stopLoop()
        c.startLoop(config, tunFd)
    }

    @Synchronized
    fun stop() {
        val c = controller ?: return
        if (c.isRunning) runCatching { c.stopLoop() }.onFailure { Log.w(TAG, "stop failed", it) }
    }

    /** One real HTTP request through the running core. Returns milliseconds, or -1 on failure. */
    fun measureDelay(url: String): Long {
        val c = synchronized(this) { controller } ?: return -1
        if (!c.isRunning) return -1
        return runCatching { c.measureDelay(url) }.getOrDefault(-1L)
    }

    /** Tests a server with a throw-away core instance (no TUN). Returns milliseconds, or -1. */
    fun testServer(context: Context, config: String, url: String): Long {
        controller(context)
        return runCatching { Libv2ray.measureOutboundDelay(config, url) }.getOrDefault(-1L)
    }

    fun version(context: Context): String =
        runCatching { controller(context); Libv2ray.checkVersionX() }.getOrDefault("?")

    /**
     * Which app a connection in the tunnel belongs to. Android answers only the
     * VPN app, and only for connections that went through its interface.
     */
    private class AppFinder(context: Context) : ProcessFinder {
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        private var lastWarning = 0L

        /** Xray asks once per app rule for the same connection: recent answers by connection. */
        private val recent = object : LinkedHashMap<String, Pair<Int, Long>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Long>>?) = size > 256
        }

        override fun findProcessByConnection(network: String?, srcIP: String?, srcPort: Long, destIP: String?, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < 29 || srcIP == null || destIP == null) return -1
            val key = "$network $srcIP $srcPort $destIP $destPort"
            val now = SystemClock.elapsedRealtime()
            synchronized(recent) { recent[key]?.takeIf { now - it.second < 2_000 }?.let { return it.first.toLong() } }
            val uid = runCatching {
                val protocol = if (network == "udp") OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
                connectivity.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(InetAddresses.parseNumericAddress(srcIP), srcPort.toInt()),
                    InetSocketAddress(InetAddresses.parseNumericAddress(destIP), destPort.toInt()),
                )
            }.getOrDefault(-1)
            synchronized(recent) { recent[key] = uid to now }
            if (uid < 0) {
                if (now - lastWarning > 60_000) {
                    lastWarning = now
                    AppLog.w(TAG, "unknown app for a $network connection to port $destPort: dropped unless DNS (logged once a minute)")
                }
            }
            return uid.toLong()
        }
    }

    private object Callback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long {
            AppLog.i(TAG, "core[$code] $message")
            return 0
        }
    }
}
