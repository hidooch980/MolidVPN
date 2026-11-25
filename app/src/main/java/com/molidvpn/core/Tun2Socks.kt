package com.molidvpn.core

import android.os.ParcelFileDescriptor
import android.util.Log
import ca.psiphon.Tun2SocksJniLoader

/**
 * badvpn tun2socks (libtun2socks.so): carries the whole device's TCP and UDP
 * from the TUN fd into a local SOCKS5 proxy. UDP goes through udpgw, which the
 * Psiphon server intercepts, so DNS rides the same tunnel.
 *
 * The JNI class must stay ca.psiphon.Tun2SocksJniLoader (the native library
 * registers itself against that name).
 */
object Tun2Socks {
    private const val TAG = "Tun2Socks"
    private const val UDPGW_PORT = 7300
    private const val NETMASK = "255.255.255.0"
    const val MTU = 1500

    @Volatile private var running = false
    private var lastThread: Thread? = null

    /**
     * badvpn initialises its globals (BTime, lwIP) only once per process, and
     * aborts on a second run. Set once a session has run, so callers can ask
     * for a fresh process instead of crashing the app.
     */
    @Volatile var needsProcessRestart = false
        private set

    private var nativeStarted = false

    /** Starts routing the TUN into [socksPort]. Takes its own dup of [tun]. */
    @Synchronized
    fun start(tun: ParcelFileDescriptor, socksPort: Int, router: String): Boolean {
        // The native globals are process-wide: never run two instances at once.
        lastThread?.let {
            if (it.isAlive) it.join(5_000)
            if (it.isAlive) {
                Log.w(TAG, "previous tun2socks still shutting down")
                return false
            }
        }
        lastThread = null
        if (nativeStarted) {
            Log.e(TAG, "tun2socks already ran in this process; it cannot be initialised twice")
            needsProcessRestart = true
            return false
        }
        val rawFd = try { tun.dup().detachFd() } catch (e: Exception) {
            Log.w(TAG, "dup tun fd failed: ${e.message}")
            return false
        }
        try {
            Tun2SocksJniLoader.initializeLogger("com.molidvpn.core.Tun2Socks", "log")
        } catch (e: Throwable) {
            Log.w(TAG, "logger init failed: ${e.message}")
        }
        running = true
        val thread = Thread({
            try {
                Tun2SocksJniLoader.runTun2Socks(
                    rawFd,
                    MTU,
                    router,
                    NETMASK,
                    null, // IPv4 only; IPv6 is blackholed by the TUN route
                    "127.0.0.1:$socksPort",
                    "127.0.0.1:$UDPGW_PORT",
                    1, // DNS goes through udpgw too
                    0,
                )
            } catch (e: Throwable) {
                Log.e(TAG, "tun2socks crashed: ${e.message}")
            } finally {
                running = false
            }
        }, "tun2socks")
        thread.start()
        lastThread = thread
        nativeStarted = true
        return true
    }

    /** Asks the native loop to exit. Returns immediately. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            Tun2SocksJniLoader.terminateTun2Socks()
        } catch (e: Throwable) {
            Log.w(TAG, "stop error: ${e.message}")
        }
    }

    /** Called from native code; the name is bound by [start]'s initializeLogger. */
    @JvmStatic
    fun log(level: String?, channel: String?, msg: String?) {
        if (level == "ERROR" || level == "WARNING") Log.w(TAG, "$level($channel): $msg")
    }
}
