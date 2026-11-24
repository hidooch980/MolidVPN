package com.molidvpn.core.wg

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the UDP socket, the handshake timer and the TUN pump. Every packet that
 * leaves the tunnel is sealed with the current session key, every packet that
 * arrives is verified, replayed-checked and written back to the interface.
 */
class WgEngine(
    private val cfg: WgConfig,
    private val tun: ParcelFileDescriptor,
    private val protectSocket: (DatagramSocket) -> Boolean,
    private val listener: Listener,
) {

    enum class State { HANDSHAKING, UP, FAILED }

    interface Listener {
        fun onState(state: State, detail: String)
        fun onTick(rxBytes: Long, txBytes: Long, handshakeAgeSec: Int, handshakes: Int)
    }

    private val running = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var session: WgSession? = null
    @Volatile private var handshake: WgHandshake? = null
    @Volatile private var lastInitiation = 0L
    @Volatile private var lastHandshake = 0L
    @Volatile private var lastSent = 0L
    @Volatile private var rx = 0L
    @Volatile private var tx = 0L
    @Volatile private var handshakes = 0
    private var threads = mutableListOf<Thread>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
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
        spawn("wg-pump") { pumpLoop() }
        spawn("wg-timer") { timerLoop(s) }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { socket?.close() } catch (_: Exception) {}
        session = null
        for (t in threads.toList()) t.interrupt()
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

    private fun sendInitiation() {
        val hs = WgHandshake(cfg.privateKey, cfg.peerPublicKey)
        handshake = hs
        lastInitiation = System.currentTimeMillis()
        send(hs.createInitiation())
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(65535)
        while (running.get()) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running.get()) listener.onState(State.FAILED, "ارتباط UDP قطع شد")
                return
            }
            if (p.length < 4) continue
            val msg = buf.copyOf(p.length)
            when (msg[0].toInt()) {
                WgHandshake.TYPE_RESPONSE -> onResponse(msg)
                WgHandshake.TYPE_TRANSPORT -> onTransport(msg)
                WgHandshake.TYPE_COOKIE -> lastInitiation = 0L
            }
        }
    }

    private fun onResponse(msg: ByteArray) {
        val hs = handshake ?: return
        if (!hs.consumeResponse(msg)) {
            android.util.Log.d("WgEngine", "response rejected at " + hs.lastError)
            return
        }
        session = WgSession(hs.session())
        lastHandshake = System.currentTimeMillis()
        handshakes++
        listener.onState(State.UP, "")
        session?.let { send(it.keepalive()) }
    }

    private fun onTransport(msg: ByteArray) {
        val s = session ?: return
        val plain = s.open(msg) ?: return
        if (plain.isEmpty()) return
        rx += plain.size
        try {
            FileOutputStream(tun.fileDescriptor).write(plain)
        } catch (_: Exception) {}
    }

    private fun pumpLoop() {
        val input = try { FileInputStream(tun.fileDescriptor) } catch (e: Exception) { return }
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
                if (now - lastInitiation > 5_000) sendInitiation()
            } else {
                if (now - lastHandshake > 120_000) {
                    session = null
                    sendInitiation()
                } else if (cfg.keepalive > 0 && now - lastSent > cfg.keepalive * 1000L) {
                    send(current.keepalive())
                }
            }
            if (now - lastTick >= 1000) {
                lastTick = now
                val age = if (lastHandshake == 0L) 0 else ((now - lastHandshake) / 1000).toInt()
                listener.onTick(rx, tx, age, handshakes)
            }
        }
    }
}
