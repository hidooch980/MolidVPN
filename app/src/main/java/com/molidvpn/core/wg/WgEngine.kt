package com.molidvpn.core.wg

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the UDP socket, the handshake timer and the TUN pump. Every packet that
 * leaves the tunnel is sealed with the current session key, every packet that
 * arrives is verified, replayed-checked and written back to the interface.
 *
 * Connection behaviour follows the WireGuard protocol: cookie replies are
 * answered with MAC2, rekeys overlap the live session so traffic keeps moving,
 * and a silent endpoint is rotated through the WARP anycast addresses.
 */
class WgEngine(
    private val cfg: WgConfig,
    /**
     * Creates the TUN only once the server has actually answered. Android shows
     * the VPN key (and routes every packet into the interface) the moment
     * [android.net.VpnService.Builder.establish] returns, so building it up
     * front would claim a tunnel that does not exist yet.
     */
    private val openTun: () -> ParcelFileDescriptor?,
    private val protectSocket: (DatagramSocket) -> Boolean,
    private val listener: Listener,
) {

    enum class State { HANDSHAKING, UP, FAILED }

    interface Listener {
        fun onState(state: State, detail: String)
        fun onTick(rxBytes: Long, txBytes: Long, handshakeAgeSec: Int, handshakes: Int)
    }

    private companion object {
        const val REKEY_AFTER_MS = 120_000L
        const val DROP_AFTER_MS = 180_000L
        const val RETRY_MS = 5_000L
        const val COOKIE_VALID_MS = 120_000L
        const val ROTATE_AFTER_TRIES = 2
        const val FIRST_HANDSHAKE_DEADLINE_MS = 150_000L
        const val RECONNECT_DEADLINE_MS = 90_000L
        const val PREVIOUS_SESSION_MS = 10_000L
    }

    private val running = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var session: WgSession? = null
    /** The session being replaced: kept briefly so packets in flight still arrive. */
    @Volatile private var previous: WgSession? = null
    @Volatile private var previousAt = 0L
    @Volatile private var handshake: WgHandshake? = null
    @Volatile private var lastInitiation = 0L
    @Volatile private var lastHandshake = 0L
    /** When the live session was lost, 0 while connected or never up yet. */
    @Volatile private var downSince = 0L
    @Volatile private var lastSent = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var rx = 0L
    @Volatile private var tx = 0L
    @Volatile private var handshakes = 0
    @Volatile private var cookie: ByteArray? = null
    @Volatile private var cookieAt = 0L

    private val endpoints = WarpEndpoints.forConfig(cfg)
    private var endpointIndex = 0
    private var silentTries = 0
    private val threads = CopyOnWriteArrayList<Thread>()
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunIn: FileInputStream? = null
    @Volatile private var tunOut: FileOutputStream? = null
    /** Endpoints already tried in this session, so the search can end honestly. */
    private val tried = HashSet<Int>().apply { add(0) }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startedAt = System.currentTimeMillis()
        listener.onState(State.HANDSHAKING, "")
        // Socket setup does disk/network work, so it never runs on the caller's thread.
        spawn("wg-net") { bootstrap() }
    }

    private fun bootstrap() {
        val s = DatagramSocket()
        socket = s
        try {
            protectSocket(s)
            s.connect(InetSocketAddress(cfg.endpointHost, cfg.endpointPort))
            s.soTimeout = 400
            if (cfg.bufferBytes > 0) {
                try {
                    s.receiveBufferSize = cfg.bufferBytes
                    s.sendBufferSize = cfg.bufferBytes
                } catch (e: Exception) {
                    android.util.Log.w("WgEngine", "socket buffer: " + e.message)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WgEngine", "socket " + cfg.endpointHost + ":" + cfg.endpointPort, e)
            running.set(false)
            listener.onState(State.FAILED, "دسترسی به " + cfg.endpoint + " ممکن نشد")
            try { s.close() } catch (_: Exception) {}
            return
        }
        if (!running.get()) {
            try { s.close() } catch (_: Exception) {}
            return
        }
        handshake = WgHandshake(cfg.privateKey, cfg.peerPublicKey)
        spawn("wg-rx") { receiveLoop(s) }
        spawn("wg-timer") { timerLoop(s) }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { socket?.close() } catch (_: Exception) {}
        session = null
        previous = null
        // Closing the streams is what unblocks the pump's pending read.
        try { tunIn?.close() } catch (_: Exception) {}
        try { tunOut?.close() } catch (_: Exception) {}
        tunIn = null
        tunOut = null
        for (t in threads) t.interrupt()
        threads.clear()
    }

    private fun spawn(name: String, body: () -> Unit) {
        val t = Thread({
            try { body() } catch (_: Throwable) {}
        }, name)
        t.isDaemon = true
        threads.add(t)
        t.start()
    }

    private fun send(data: ByteArray) {
        val s = socket ?: return
        try {
            s.send(DatagramPacket(data, data.size))
            lastSent = System.currentTimeMillis()
        } catch (_: Exception) {}
    }

    private fun sendInitiation(now: Long) {
        val hs = WgHandshake(cfg.privateKey, cfg.peerPublicKey)
        handshake = hs
        lastInitiation = now
        val c = cookie?.takeIf { now - cookieAt < COOKIE_VALID_MS }
        send(hs.createInitiation(c))
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(65535)
        while (running.get()) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: PortUnreachableException) {
                // An ICMP "port unreachable" from the network is not a dead
                // tunnel: keep reading and let the timer retry or rotate the
                // endpoint instead of killing a usable connection.
                android.util.Log.w("WgEngine", "endpoint answered ICMP unreachable; retrying")
                lastInitiation = 0L
                continue
            } catch (e: Exception) {
                android.util.Log.e("WgEngine", "receive failed", e)
                if (running.get()) listener.onState(State.FAILED, "ارتباط UDP قطع شد")
                return
            }
            if (p.length < 4) continue
            val msg = buf.copyOf(p.length)
            when (msg[0].toInt()) {
                WgHandshake.TYPE_RESPONSE -> onResponse(msg)
                WgHandshake.TYPE_TRANSPORT -> onTransport(msg)
                WgHandshake.TYPE_COOKIE -> onCookie(msg)
            }
        }
    }

    private fun onCookie(msg: ByteArray) {
        val hs = handshake ?: return
        val c = hs.openCookie(msg) ?: return
        cookie = c
        cookieAt = System.currentTimeMillis()
        // The responder asked for MAC2: answer right away instead of waiting for the retry timer.
        lastInitiation = 0L
    }

    private fun onResponse(msg: ByteArray) {
        val hs = handshake ?: return
        if (!hs.consumeResponse(msg)) {
            android.util.Log.d("WgEngine", "response rejected at " + hs.lastError)
            return
        }
        val now = System.currentTimeMillis()
        // The handshake is real at this point, so the interface can exist: this
        // is the first moment the VPN key is allowed to appear.
        if (!attachTun()) {
            running.set(false)
            listener.onState(State.FAILED, "دست‌دادن انجام شد ولی ساخت رابط تونل ممکن نشد")
            return
        }
        val old = session
        if (old != null) {
            previous = old
            previousAt = now
        }
        session = WgSession(hs.session())
        lastHandshake = now
        handshakes++
        silentTries = 0
        tried.clear()
        tried.add(0)
        downSince = 0L
        listener.onState(State.UP, "")
        session?.let { send(it.keepalive()) }
    }

    /** Opens the interface and starts the pump; only called after a handshake. */
    private fun attachTun(): Boolean {
        if (tun != null) return true
        val fd = try { openTun() } catch (e: Exception) {
            android.util.Log.e("WgEngine", "establish tun", e)
            null
        } ?: return false
        val out = try { FileOutputStream(fd.fileDescriptor) } catch (e: Exception) { null }
        val input = try { FileInputStream(fd.fileDescriptor) } catch (e: Exception) { null }
        if (out == null || input == null) {
            try { fd.close() } catch (_: Exception) {}
            return false
        }
        tun = fd
        tunOut = out
        tunIn = input
        spawn("wg-pump") { pumpLoop() }
        return true
    }

    private fun onTransport(msg: ByteArray) {
        val plain = session?.open(msg) ?: previous?.open(msg) ?: return
        if (plain.isEmpty()) return
        rx += plain.size
        try {
            tunOut?.write(plain)
        } catch (_: Exception) {}
    }

    private fun pumpLoop() {
        val input = tunIn ?: return
        val buf = ByteArray(32768)
        while (running.get()) {
            val n = try { input.read(buf) } catch (e: Exception) { -1 }
            if (n <= 0) {
                if (!running.get()) return
                continue
            }
            val s = session ?: continue
            val packet = buf.copyOf(n)
            send(s.seal(packet))
            tx += n
        }
    }

    private fun timerLoop(s: DatagramSocket) {
        var lastTick = 0L
        while (running.get()) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { return }
            val now = System.currentTimeMillis()
            val current = session
            if (current == null) {
                if (lastHandshake == 0L && now - startedAt > FIRST_HANDSHAKE_DEADLINE_MS) {
                    // Reported as failed; TunnelService tears the engine down through stop().
                    listener.onState(State.FAILED, "پاسخی از سرور نرسید؛ پیکربندی یا مسیر را بررسی کن")
                    return
                }
                // A session that never comes back must not leave the UI lying
                // "connecting" forever.
                if (lastHandshake != 0L) {
                    if (downSince == 0L) downSince = now
                    if (now - downSince > RECONNECT_DEADLINE_MS) {
                        listener.onState(State.FAILED, "ارتباط با سرور برقرار نشد؛ دوباره تلاش کن")
                        return
                    }
                }
                if (now - lastInitiation > RETRY_MS) {
                    silentTries++
                    if (silentTries >= ROTATE_AFTER_TRIES && !rotateEndpoint(s)) {
                        // Every candidate stayed silent: say so, and stop claiming
                        // the tunnel is still coming up.
                        listener.onState(
                            State.FAILED,
                            "هیچ‌کدام از " + endpoints.size + " مسیر امتحان‌شده پاسخ نداد؛ " +
                                "شبکه احتمالاً UDP را مسدود می‌کند",
                        )
                        return
                    }
                    sendInitiation(now)
                }
            } else {
                val age = now - lastHandshake
                if (age > DROP_AFTER_MS) {
                    // Too old to keep using: fall back to a fresh handshake and
                    // say so, instead of leaving the green "connected" badge on
                    // a dead session.
                    session = null
                    previous = null
                    lastInitiation = 0L
                    downSince = now
                    listener.onState(State.HANDSHAKING, "ارتباط قطع شد؛ در حال اتصال دوباره…")
                } else if (age > REKEY_AFTER_MS) {
                    // Rekey while the current session still carries traffic.
                    if (now - lastInitiation > RETRY_MS) sendInitiation(now)
                } else if (cfg.keepalive > 0 && now - lastSent > cfg.keepalive * 1000L) {
                    send(current.keepalive())
                }
            }
            if (previous != null && now - previousAt > PREVIOUS_SESSION_MS) previous = null
            if (now - lastTick >= 1000) {
                lastTick = now
                val age = if (lastHandshake == 0L) 0 else ((now - lastHandshake) / 1000).toInt()
                listener.onTick(rx, tx, age, handshakes)
            }
        }
    }

    /**
     * Moves to the next WARP anycast address when the current one stays silent.
     * Returns false once every candidate has been tried without an answer.
     */
    private fun rotateEndpoint(s: DatagramSocket): Boolean {
        if (endpoints.size < 2 || tried.size >= endpoints.size) return false
        do {
            endpointIndex = (endpointIndex + 1) % endpoints.size
        } while (!tried.add(endpointIndex))
        val (host, port) = endpoints[endpointIndex]
        silentTries = 0
        cookie = null
        lastInitiation = 0L
        try {
            // Re-connecting the same protected socket keeps its VPN bypass.
            s.disconnect()
            s.connect(InetSocketAddress(host, port))
        } catch (e: Exception) {
            android.util.Log.w("WgEngine", "endpoint " + host + ":" + port + " unusable", e)
        }
        return true
    }
}
