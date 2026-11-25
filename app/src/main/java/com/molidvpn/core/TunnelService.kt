package com.molidvpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.molidvpn.MainActivity
import com.molidvpn.R
import com.molidvpn.core.cores.SocksCore
import com.molidvpn.core.psi.PsiphonEngine
import com.molidvpn.data.ProfileStore
import com.molidvpn.core.wg.TunnelProfiles
import com.molidvpn.core.wg.WgConfig
import com.molidvpn.core.wg.WgEngine
import com.molidvpn.data.SettingsStore
import java.net.DatagramSocket
import java.net.NetworkInterface

/**
 * Builds the TUN interface from the imported WireGuard configuration, then hands
 * it to the packet engine. Routes and DNS come from the same file, so nothing
 * leaks outside the tunnel once it is up.
 */
class TunnelService : VpnService() {

    companion object {
        const val ACTION_START = "com.molidvpn.START"
        const val ACTION_STOP = "com.molidvpn.STOP"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 41
        /** How long a dead Psiphon path is retried before the service gives up. */
        private const val GIVE_UP_MS = 180_000L
    }

    private var iface: ParcelFileDescriptor? = null
    private var tunAddr: TunAddress? = null
    private var udpgw: UdpGwBridge? = null
    private var engine: WgEngine? = null
    private var psiphon: PsiphonEngine? = null
    private var core: SocksCore? = null
    /** Modes still to try, in order, when Smart mode's current path fails. */
    private val fallback = ArrayDeque<String>()
    private var psiRx = 0L
    private var psiTx = 0L
    /** Psiphon health watchdog: the core can claim "connected" while relaying nothing. */
    private var watchdog: Thread? = null
    @Volatile private var shuttingDown = false
    private var psiFailures = 0
    /** Uptime millis when the data path first went quiet for good, 0 while healthy. */
    private var deadSince = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastTick = 0L

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "اتصال", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (iface == null && psiphon == null && core == null) establish()
        return START_NOT_STICKY
    }

    private fun fail(message: String) {
        shuttingDown = true
        VpnState.status = VpnStatus.FAILED
        VpnState.detail = message
        teardownInterface()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun establish() {
        when (SettingsStore(this).mode) {
            "psiphon" -> { startPsiphon(); return }
            "tor" -> { startCore(SocksCore.Kind.TOR, null); return }
            "xray" -> {
                val link = firstXrayLink()
                if (link == null) {
                    fail("هیچ لینک SS/VLESS وارد نشده؛ در تنظیمات «افزودن از لینک» را بزن")
                    return
                }
                startCore(SocksCore.Kind.XRAY, link)
                return
            }
            "smart" -> {
                // Smart: WARP first when a config exists, then the in-app cores in order.
                fallback.clear()
                fallback.addAll(listOf("psiphon", "tor", "xray"))
                if (SettingsStore(this).wgConfig.isBlank()) {
                    nextFallback("")
                    return
                }
            }
        }
        VpnState.status = VpnStatus.CONNECTING
        VpnState.detail = ""
        VpnState.engine = "wireguard"

        val raw = SettingsStore(this).wgConfig
        if (raw.isBlank()) {
            fail("پیکربندی تونل وارد نشده")
            return
        }
        val cfg = try {
            WgConfig.parse(raw).tuned(TunnelProfiles.byId(SettingsStore(this).wgProfile))
        } catch (e: Exception) {
            fail(e.message ?: "پیکربندی نامعتبر است")
            return
        }

        startForegroundCompat(buildNotification("در حال دست‌دادن…"))

        val builder = Builder()
            .setSession("MolidoVPN")
            .setMtu(cfg.mtu)
        try {
            for (addr in cfg.addresses) {
                val ip = addr.substringBefore('/')
                val prefix = addr.substringAfter('/', "32").toIntOrNull() ?: 32
                builder.addAddress(ip, prefix)
            }
            for (dns in cfg.dns) builder.addDnsServer(dns)
            for (route in cfg.allowedIps) {
                val ip = route.substringBefore('/')
                val prefix = route.substringAfter('/', "0").toIntOrNull() ?: 0
                builder.addRoute(ip, prefix)
            }
            builder.setBlocking(false)
        } catch (e: Exception) {
            fail("آدرس‌های پیکربندی معتبر نیستند")
            return
        }

        // Deliberately not built yet: the engine calls this only after the
        // server has answered the handshake, so the system VPN key and the
        // full-device routing never exist for a tunnel that is not up.
        val openTun: () -> ParcelFileDescriptor? = {
            val t = try {
                builder.establish()
            } catch (e: Exception) {
                Log.e("MolidVpn", "establish tun failed", e)
                null
            }
            if (t != null) iface = t
            t
        }

        val eng = WgEngine(
            cfg = cfg,
            openTun = openTun,
            protectSocket = { s: DatagramSocket -> protect(s) },
            listener = object : WgEngine.Listener {
                override fun onState(state: WgEngine.State, detail: String) {
                    when (state) {
                        WgEngine.State.HANDSHAKING -> {
                            VpnState.status = VpnStatus.CONNECTING
                            // A fresh session is starting: the old uptime no longer applies.
                            VpnState.startedAt = 0L
                            VpnState.detail = detail
                        }
                        WgEngine.State.UP -> {
                            val resumed = VpnState.status == VpnStatus.CONNECTED
                            VpnState.status = VpnStatus.CONNECTED
                            // Rekeys arrive as UP too; they must not restart the clock.
                            if (!resumed) VpnState.startedAt = System.currentTimeMillis()
                            VpnState.detail = ""
                        }
                        WgEngine.State.FAILED -> {
                            // Terminal: drop the TUN too, otherwise it blackholes all traffic.
                            failOrNext(detail)
                            return
                        }
                    }
                    notifyText()
                }

                override fun onTick(rxBytes: Long, txBytes: Long, handshakeAgeSec: Int, handshakes: Int) {
                    publishTraffic(rxBytes, txBytes)
                    VpnState.handshakeAge = handshakeAgeSec
                    VpnState.handshakes = handshakes
                    // Keeps the ongoing notification's handshake count honest.
                    notifyText()
                }
            },
        )
        engine = eng
        eng.start()
    }

    /**
     * In-app Psiphon: the tunnel starts first (it must connect before the TUN
     * exists, or its own sockets would loop back into the TUN). Once it reports
     * a SOCKS port, [bridgePsiphon] puts the device's traffic into that port.
     */
    /** Smart mode: when a path dies before carrying traffic, move on to the next core. */
    private fun failOrNext(detail: String) {
        if (fallback.isEmpty()) {
            fail(detail)
            return
        }
        // Off the engine thread: teardown joins the engine it is called from.
        Thread({ nextFallback(detail) }, "fallback").apply { isDaemon = true }.start()
    }

    private fun nextFallback(previousDetail: String) {
        teardownInterface()
        val mode = fallback.removeFirstOrNull()
        when (mode) {
            "psiphon" -> startPsiphon()
            "tor" -> startCore(SocksCore.Kind.TOR, null)
            "xray" -> {
                val link = firstXrayLink()
                if (link == null) nextFallback(previousDetail) else startCore(SocksCore.Kind.XRAY, link)
            }
            else -> fail(previousDetail.ifBlank { "هیچ مسیری برقرار نشد" })
        }
    }

    /** The first share link Xray can run, if any has been imported or provisioned. */
    private fun firstXrayLink(): String? =
        ProfileStore(this).all.firstOrNull { p ->
            listOf("vless://", "vmess://", "trojan://", "ss://").any { p.raw.startsWith(it) }
        }?.raw

    /** Runs Tor or Xray as a bundled core; its SOCKS port is then bridged like Psiphon's. */
    private fun startCore(kind: SocksCore.Kind, link: String?) {
        VpnState.status = VpnStatus.CONNECTING
        VpnState.detail = ""
        VpnState.engine = if (kind == SocksCore.Kind.TOR) "tor" else "xray"
        startForegroundCompat(buildNotification("در حال اتصال به ${if (kind == SocksCore.Kind.TOR) "تور" else "شادوساکس"}…"))
        val started = SocksCore(this, kind, link, object : SocksCore.Listener {
            override fun onReady(socksPort: Int) = bridgePsiphon(socksPort)
            override fun onFailed(detail: String) = failOrNext(detail)
        }).also { core = it }
        if (!started.start()) {
            core = null
            failOrNext("هسته اجرا نشد")
        }
    }

    private fun startPsiphon() {
        VpnState.status = VpnStatus.CONNECTING
        VpnState.detail = ""
        VpnState.engine = "psiphon"
        startForegroundCompat(buildNotification("در حال اتصال به سایفون…"))
        val eng = PsiphonEngine(
            context = this,
            protect = { fd -> protect(fd) },
            listener = object : PsiphonEngine.Listener {
                override fun onReady(socksPort: Int) = bridgePsiphon(socksPort)

                override fun onBytes(sent: Long, received: Long) {
                    psiTx += sent
                    psiRx += received
                    publishTraffic(psiRx, psiTx)
                }

                override fun onFailed(detail: String) = failOrNext(detail)
            },
        )
        psiphon = eng
        eng.start()
        startWatchdog()
    }

    /**
     * Watches the Psiphon data path and reports what it actually does.
     *
     * The core re-establishes its own tunnels, so this never restarts it: the
     * bundled badvpn tun2socks can only be initialised once per process, so a
     * second start aborts the app. Health is judged by real traffic first, then
     * by a SOCKS probe; a dead path is shown as reconnecting instead of the
     * "connected" the core keeps insisting on.
     */
    private fun startWatchdog() {
        synchronized(this) {
            if (watchdog?.isAlive == true) return
            watchdog = Thread({ watchLoop() }, "psi-watchdog").apply { isDaemon = true; start() }
        }
    }

    private fun watchLoop() {
        while (!shuttingDown) {
            try {
                Thread.sleep(6_000)
            } catch (_: InterruptedException) {
                return
            }
            if (shuttingDown) return
            val eng = psiphon ?: continue
            if (iface == null) continue
            val quiet = SystemClock.elapsedRealtime() - eng.lastActivityAt
            if (quiet < 30_000L) {
                psiFailures = 0
                healthy()
                continue
            }
            if (eng.probe()) {
                psiFailures = 0
                healthy()
                continue
            }
            psiFailures++
            if (psiFailures < 2) continue
            // The core still claims a tunnel, but nothing goes through it.
            if (deadSince == 0L) deadSince = SystemClock.elapsedRealtime()
            if (VpnState.status == VpnStatus.CONNECTED) {
                VpnState.status = VpnStatus.CONNECTING
                VpnState.detail = "ارتباط سایفون قطع شد؛ در حال اتصال دوباره…"
                notifyText()
            }
            if (SystemClock.elapsedRealtime() - deadSince > GIVE_UP_MS) {
                fail("سایفون نتوانست اتصال را نگه دارد؛ دوباره تلاش کن")
                return
            }
        }
    }

    /** Marks the Psiphon path healthy again after a probe that got through. */
    private fun healthy() {
        deadSince = 0L
        if (psiphon == null) return
        if (VpnState.status == VpnStatus.CONNECTING && iface != null) {
            VpnState.status = VpnStatus.CONNECTED
            VpnState.detail = ""
            notifyText()
        }
    }

    /** Creates the TUN interface; called once the Psiphon SOCKS port is ready. */
    private fun establishTun(): Boolean {
        val addr = pickTunAddress()
        val builder = Builder()
            .setSession("MolidoVPN · سایفون")
            .setMtu(Tun2Socks.MTU)
        // The cores run inside this app; excluding it keeps their own sockets out of the TUN.
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        try {
            builder.addAddress(addr.ip, addr.prefix)
            builder.addRoute("0.0.0.0", 0)
            builder.addDnsServer("1.1.1.1")
        } catch (e: Exception) {
            fail("آدرس رابط تونل معتبر نیست")
            return false
        }
        // Blackhole IPv6 so it cannot leak around the IPv4-only tunnel.
        try { builder.addRoute("::", 0) } catch (_: Exception) {}
        val tun = try { builder.establish() } catch (e: Exception) { null }
        if (tun == null) {
            fail("ساخت رابط تونل ممکن نشد")
            return false
        }
        iface = tun
        tunAddr = addr
        return true
    }

    private fun bridgePsiphon(socksPort: Int) {
        if (iface != null) return
        if (!establishTun()) return
        val tun = iface ?: return
        val addr = tunAddr ?: return
        // DNS rides DNS-over-TCP through the same SOCKS tunnel (see UdpGwBridge).
        udpgw = UdpGwBridge(socksPort).also { bridge ->
            if (!bridge.start()) Log.w("MolidVpn", "udpgw bridge did not start")
        }
        // tun2socks must be told the address the interface itself carries.
        if (!Tun2Socks.start(tun, socksPort, addr.ip)) {
            fail(
                if (Tun2Socks.needsProcessRestart) {
                    "این نشست تونل بسته شده؛ برای اتصال دوباره برنامه را کامل ببند و باز کن"
                } else {
                    "موتور تونل اجرا نشد"
                }
            )
            return
        }
        // A core that says "connected" is not proof. One real request has to
        // pass through its SOCKS port before the screen is allowed to say so.
        VpnState.status = VpnStatus.CONNECTING
        VpnState.detail = "در حال بررسی مسیر داده…"
        notifyText()
        Thread({
            val ok = try {
                psiphon?.probe() ?: SocksCore.probe(socksPort)
            } catch (_: Throwable) {
                false
            }
            if (shuttingDown || iface == null) return@Thread
            if (ok) {
                VpnState.status = VpnStatus.CONNECTED
                VpnState.startedAt = System.currentTimeMillis()
                VpnState.detail = ""
                psiFailures = 0
                deadSince = 0L
            } else {
                // Still connecting: the watchdog keeps probing and will promote it.
                VpnState.detail = "مسیر داده هنوز پاسخ نمی‌دهد؛ در حال تلاش…"
            }
            notifyText()
        }, "verify-path").start()
    }

    /** Picks a private /8–/24 that no local interface already uses. */
    private fun pickTunAddress(): TunAddress {
        val used = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .flatMap { it.inetAddresses.toList() }
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }
        val candidates = listOf(
            "10." to TunAddress("10.0.0.1", 8, "10.0.0.2"),
            "172.16." to TunAddress("172.16.0.1", 12, "172.16.0.2"),
            "192.168." to TunAddress("192.168.0.1", 16, "192.168.0.2"),
            "169.254." to TunAddress("169.254.1.1", 24, "169.254.1.2"),
        )
        return candidates.firstOrNull { (prefix, _) -> used.none { it.startsWith(prefix) } }?.second
            ?: candidates[0].second
    }

    private data class TunAddress(val ip: String, val prefix: Int, val router: String)

    /** Publishes totals and derived Mbps. Callers pass cumulative byte counts. */
    @Synchronized
    private fun publishTraffic(rxBytes: Long, txBytes: Long) {
        val now = System.currentTimeMillis()
        if (lastTick != 0L) {
            val span = (now - lastTick).coerceAtLeast(1) / 1000.0
            VpnState.rxMbps = ((rxBytes - lastRx) * 8.0 / 1_000_000.0 / span).coerceIn(0.0, 999.0)
            VpnState.txMbps = ((txBytes - lastTx) * 8.0 / 1_000_000.0 / span).coerceIn(0.0, 999.0)
        }
        lastTick = now
        lastRx = rxBytes
        lastTx = txBytes
        VpnState.rxBytes = rxBytes
        VpnState.txBytes = txBytes
    }

    private fun notifyText() {
        if (VpnState.status == VpnStatus.FAILED) {
            teardownInterface()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val text = when (VpnState.status) {
            VpnStatus.CONNECTED ->
                if (psiphon != null) "سایفون متصل است"
                else "تونل فعال است · " + VpnState.handshakes + " دست‌دادن"
            VpnStatus.CONNECTING ->
                if (psiphon != null || VpnState.detail.isNotEmpty()) {
                    VpnState.detail.ifEmpty { "در حال اتصال به سایفون…" }
                } else {
                    "در حال دست‌دادن با سرور…"
                }
            else -> "آماده"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String = "اتصال امن برقرار است"): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("MolidoVPN")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun teardownInterface() {
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        Tun2Socks.stop()
        udpgw?.stop()
        udpgw = null
        psiphon?.stop()
        psiphon = null
        core?.stop()
        core = null
        psiRx = 0L
        psiTx = 0L
        lastTick = 0L
        try { iface?.close() } catch (_: Exception) {}
        iface = null
    }

    private fun shutdown() {
        shuttingDown = true
        teardownInterface()
        VpnState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        shutdown()
    }

    override fun onDestroy() {
        val wasActive = VpnState.status == VpnStatus.CONNECTED ||
            VpnState.status == VpnStatus.CONNECTING
        shuttingDown = true
        teardownInterface()
        // A service the OS tears down while it was live must not silently look
        // idle: that is a failed tunnel, not a user-requested disconnect.
        if (VpnState.status != VpnStatus.FAILED) {
            if (wasActive) {
                VpnState.status = VpnStatus.FAILED
                VpnState.startedAt = 0L
                VpnState.handshakeAge = 0
                VpnState.detail = "ارتباط قطع شد؛ دوباره تلاش کن"
            } else {
                VpnState.reset()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
