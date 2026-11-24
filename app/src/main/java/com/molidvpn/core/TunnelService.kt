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
import androidx.core.app.NotificationCompat
import com.molidvpn.MainActivity
import com.molidvpn.R
import com.molidvpn.core.wg.WgConfig
import com.molidvpn.core.wg.WgEngine
import com.molidvpn.data.SettingsStore
import java.net.DatagramSocket

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
    }

    private var iface: ParcelFileDescriptor? = null
    private var engine: WgEngine? = null
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
        if (iface == null) establish()
        return START_NOT_STICKY
    }

    private fun fail(message: String) {
        VpnState.status = VpnStatus.FAILED
        VpnState.detail = message
        teardownInterface()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun establish() {
        VpnState.status = VpnStatus.CONNECTING
        VpnState.detail = ""

        val raw = SettingsStore(this).wgConfig
        if (raw.isBlank()) {
            fail("پیکربندی تونل وارد نشده")
            return
        }
        val cfg = try {
            WgConfig.parse(raw)
        } catch (e: Exception) {
            fail(e.message ?: "پیکربندی نامعتبر است")
            return
        }

        startForegroundCompat(buildNotification("در حال دست‌دادن…"))

        val builder = Builder()
            .setSession("MolidVPN")
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

        val tun = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
        if (tun == null) {
            fail("ساخت رابط تونل ممکن نشد")
            return
        }
        iface = tun

        val eng = WgEngine(
            cfg = cfg,
            tun = tun,
            protectSocket = { s: DatagramSocket -> protect(s) },
            listener = object : WgEngine.Listener {
                override fun onState(state: WgEngine.State, detail: String) {
                    when (state) {
                        WgEngine.State.HANDSHAKING -> VpnState.status = VpnStatus.CONNECTING
                        WgEngine.State.UP -> {
                            VpnState.status = VpnStatus.CONNECTED
                            VpnState.startedAt = System.currentTimeMillis()
                            VpnState.detail = ""
                        }
                        WgEngine.State.FAILED -> {
                            VpnState.status = VpnStatus.FAILED
                            VpnState.detail = detail
                        }
                    }
                    notifyText()
                }

                override fun onTick(rxBytes: Long, txBytes: Long, handshakeAgeSec: Int, handshakes: Int) {
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
                    VpnState.handshakeAge = handshakeAgeSec
                    VpnState.handshakes = handshakes
                }
            },
        )
        engine = eng
        eng.start()
    }

    private fun notifyText() {
        if (VpnState.status == VpnStatus.FAILED) {
            teardownInterface()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val text = when (VpnState.status) {
            VpnStatus.CONNECTED -> "تونل فعال است · " + VpnState.handshakes + " دست‌دادن"
            VpnStatus.CONNECTING -> "در حال دست‌دادن با سرور…"
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
            .setContentTitle("MolidVPN")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun teardownInterface() {
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        try { iface?.close() } catch (_: Exception) {}
        iface = null
    }

    private fun shutdown() {
        teardownInterface()
        VpnState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        shutdown()
    }

    override fun onDestroy() {
        teardownInterface()
        if (VpnState.status != VpnStatus.FAILED) VpnState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
