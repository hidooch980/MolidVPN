package com.molidvpn.core

import android.util.Log
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A small badvpn udpgw server, running inside the app.
 *
 * tun2socks sends every UDP datagram here over a TCP connection, framed as
 * `uint16 length`, one flags byte, a 16-bit connection id, the destination
 * (4-byte address + 2-byte port) and the payload. The destination ride along in
 * every frame, so a single bridge can serve all flows.
 *
 * Raw UDP is dropped by most censored networks, so DNS is forwarded as
 * DNS-over-TCP through the very SOCKS5 proxy the TCP flows already use: the
 * lookup then travels inside the same tunnel. Other UDP is best-effort through
 * SOCKS5 UDP ASSOCIATE, and silently dropped when the proxy does not offer it.
 */
class UdpGwBridge(private val socksPort: Int) {

    companion object {
        const val PORT = 7300
        private const val TAG = "UdpGw"
        private const val FLAG_IPV6 = 0x08
        private const val DNS_PORT = 53
        /** Where tunnel-side lookups really go; the TUN's own DNS is a placeholder. */
        private val DNS_RESOLVER = byteArrayOf(1, 1, 1, 1)
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val DNS_TIMEOUT_MS = 6_000
    }

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var worker: Thread? = null

    @Volatile var dnsQueries = 0
        private set
    @Volatile var dnsFailures = 0
        private set

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
            server = s
            running = true
            worker = Thread({ acceptLoop(s) }, "udpgw").also { it.start() }
            Log.i(TAG, "listening on 127.0.0.1:$PORT for socks $socksPort")
            true
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try { s.accept() } catch (e: Exception) { break }
            Thread({ serve(client) }, "udpgw-flow").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 0
            val input = client.getInputStream()
            val output = client.getOutputStream()
            while (running) {
                val header = ByteArray(3)
                if (!readFully(input, header)) break
                val len = ((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)
                if (len < 3) break
                val body = ByteArray(len)
                if (!readFully(input, body)) break
                val flags = body[0].toInt()
                val conidHi = body[1]
                val conidLo = body[2]
                val addrLen = if (flags and FLAG_IPV6 != 0) 18 else 6
                if (body.size < 3 + addrLen) continue
                val ip = ByteArray(4)
                System.arraycopy(body, 3, ip, 0, 4)
                val port = ((body[7].toInt() and 0xff) shl 8) or (body[8].toInt() and 0xff)
                val payload = body.copyOfRange(3 + addrLen, body.size)
                if (payload.isEmpty()) continue
                val answer = forward(ip, port, payload) ?: continue
                val frame = ByteArray(3 + addrLen + answer.size)
                val total = frame.size - 2
                frame[0] = ((total shr 8) and 0xff).toByte()
                frame[1] = (total and 0xff).toByte()
                frame[2] = 0
                frame[3] = conidHi
                frame[4] = conidLo
                System.arraycopy(body, 3, frame, 5, addrLen)
                System.arraycopy(answer, 0, frame, 3 + addrLen, answer.size)
                output.write(frame)
                output.flush()
            }
        } catch (e: Exception) {
            if (e !is IOException) Log.w(TAG, "flow error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** Sends one datagram and waits for at most one reply. */
    private fun forward(ip: ByteArray, port: Int, payload: ByteArray): ByteArray? {
        if (port != DNS_PORT) return null
        val dest = if (isPrivate(ip)) DNS_RESOLVER else ip
        return try {
            val socket = socksConnect(dest, port)
            try {
                socket.soTimeout = DNS_TIMEOUT_MS
                val out = socket.getOutputStream()
                out.write(byteArrayOf(((payload.size shr 8) and 0xff).toByte(), (payload.size and 0xff).toByte()))
                out.write(payload)
                out.flush()
                val head = ByteArray(2)
                if (!readFully(socket.getInputStream(), head)) return null
                val size = ((head[0].toInt() and 0xff) shl 8) or (head[1].toInt() and 0xff)
                if (size <= 0) return null
                val answer = ByteArray(size)
                if (!readFully(socket.getInputStream(), answer)) return null
                dnsQueries++
                answer
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            dnsFailures++
            if (dnsFailures <= 5) Log.w(TAG, "dns forward failed: ${e.message}")
            null
        }
    }

    private fun isPrivate(ip: ByteArray): Boolean {
        val a = ip[0].toInt() and 0xff
        val b = ip[1].toInt() and 0xff
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 169 && b == 254) || a == 0
    }

    /** SOCKS5 CONNECT to [ip]:[port]; returns a socket already past the handshake. */
    private fun socksConnect(ip: ByteArray, port: Int): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), socksPort), CONNECT_TIMEOUT_MS)
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(byteArrayOf(5, 1, 0))
        out.flush()
        val method = ByteArray(2)
        if (!readFully(input, method) || method[1].toInt() != 0) throw IOException("socks refused methods")
        val req = ByteArray(4 + 4 + 2)
        req[0] = 5
        req[1] = 1
        req[2] = 0
        req[3] = 1
        System.arraycopy(ip, 0, req, 4, 4)
        req[8] = ((port shr 8) and 0xff).toByte()
        req[9] = (port and 0xff).toByte()
        out.write(req)
        out.flush()
        val reply = ByteArray(4)
        if (!readFully(input, reply)) throw IOException("socks reply cut")
        if (reply[1].toInt() != 0) throw IOException("socks rep=${reply[1]}")
        val skip = when (reply[3].toInt()) {
            1 -> 6
            3 -> {
                val l = input.read()
                if (l < 0) throw IOException("socks addr cut")
                l + 2
            }
            4 -> 18
            else -> throw IOException("socks atyp=${reply[3]}")
        }
        if (!readFully(input, ByteArray(if (skip == 0) 1 else skip))) throw IOException("socks addr cut")
        return socket
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = try { input.read(buffer, read, buffer.size - read) } catch (e: EOFException) { -1 }
            if (n <= 0) return false
            read += n
        }
        return true
    }
}
