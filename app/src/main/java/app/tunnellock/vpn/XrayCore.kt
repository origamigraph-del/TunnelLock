package app.tunnellock.vpn

import android.content.Context
import android.util.Log
import app.tunnellock.data.AppLog
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/** Thin wrapper around the Xray core (AndroidLibXrayLite / gomobile). */
object XrayCore {
    private const val TAG = "XrayCore"

    private var controller: CoreController? = null

    @Synchronized
    private fun controller(context: Context): CoreController {
        controller?.let { return it }
        Seq.setContext(context.applicationContext)
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
        return Libv2ray.newCoreController(Callback).also { controller = it }
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

    private object Callback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long {
            AppLog.i(TAG, "core[$code] $message")
            return 0
        }
    }
}
