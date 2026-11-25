package com.molidvpn.core.psi

import android.content.Context
import android.os.SystemClock
import android.util.Log
import ca.psiphon.PsiphonTunnel
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Embedded Psiphon: runs psiphon-tunnel-core in SOCKS mode on a local port.
 * TunnelService routes the TUN device into that port with tun2socks, so the
 * user never needs a separate Psiphon app.
 */
class PsiphonEngine(
    private val context: Context,
    /** VpnService.protect — keeps Psiphon's own sockets off the TUN. */
    private val protect: (Int) -> Boolean,
    private val listener: Listener,
) : PsiphonTunnel.HostService {

    interface Listener {
        /** Tunnel is up and the local SOCKS port is listening. */
        fun onReady(socksPort: Int)
        /** Byte deltas since the previous call. */
        fun onBytes(sent: Long, received: Long)
        fun onFailed(detail: String)
    }

    private var tunnel: PsiphonTunnel? = null
    @Volatile private var socksPort = 0
    @Volatile private var connected = false
    @Volatile private var reported = false
    @Volatile private var stopped = false

    /** Uptime millis of the last byte the core moved, 0 before the first byte. */
    @Volatile var lastActivityAt = 0L
        private set

    /** Returns false (and reports via [Listener.onFailed]) if Psiphon cannot start. */
    fun start(): Boolean {
        val serverEntries = try {
            context.assets.open("server_entries.txt").bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            listener.onFailed("فهرست سرورهای سایفون در برنامه نیست")
            return false
        }
        return try {
            val t = PsiphonTunnel.newPsiphonTunnel(this)
            // SOCKS mode: tun2socks is the VPN data path, not Psiphon itself.
            t.setVpnMode(false)
            tunnel = t
            t.startTunneling(serverEntries)
            true
        } catch (e: Exception) {
            listener.onFailed("راه‌اندازی سایفون ناموفق بود: ${e.message}")
            false
        }
    }

    fun stop() {
        stopped = true
        val t = tunnel ?: return
        tunnel = null
        // Psiphon's stop() can block; keep it off the main thread.
        Thread { try { t.stop() } catch (_: Throwable) {} }.start()
    }

    private fun maybeReady() {
        if (connected && socksPort > 0 && !reported && !stopped) {
            reported = true
            listener.onReady(socksPort)
        }
    }

    /**
     * One real request through the tunnel: SOCKS5 CONNECT to a public resolver,
     * then a DNS-over-TCP lookup. A core that reports "connected" but no longer
     * relays anything answers the greeting and refuses the CONNECT, which is
     * exactly what this catches.
     */
    fun probe(timeoutMs: Int = 8_000): Boolean {
        val port = socksPort
        if (port <= 0 || !connected || stopped) return false
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()

                out.write(byteArrayOf(5, 1, 0)) // SOCKS5, one method: no auth
                out.flush()
                val greeting = ByteArray(2)
                readFully(inp, greeting)
                if (greeting[0].toInt() != 5 || greeting[1].toInt() != 0) return false

                // CONNECT 1.1.1.1:53 — the same path DNS really uses.
                out.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 0, 53))
                out.flush()
                val reply = ByteArray(4)
                readFully(inp, reply)
                if (reply[1].toInt() != 0) {
                    Log.w(TAG, "probe refused, socks code " + reply[1])
                    return false
                }
                when (reply[3].toInt()) { // drain the bound address
                    1 -> readFully(inp, ByteArray(4))
                    4 -> readFully(inp, ByteArray(16))
                    else -> {
                        val len = ByteArray(1)
                        readFully(inp, len)
                        readFully(inp, ByteArray(len[0].toInt()))
                    }
                }
                readFully(inp, ByteArray(2))

                out.write(DNS_PROBE)
                out.flush()
                val head = ByteArray(2)
                readFully(inp, head)
                val size = ((head[0].toInt() and 0xff) shl 8) or (head[1].toInt() and 0xff)
                size > 12
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe failed: " + e.message)
            false
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var filled = 0
        while (filled < buffer.size) {
            val n = input.read(buffer, filled, buffer.size - filled)
            if (n < 0) return false
            filled += n
        }
        return true
    }

    // --- HostService -------------------------------------------------------

    override fun getContext(): Context = context

    override fun getPsiphonConfig(): String {
        val dataDir = File(context.filesDir, "psiphon").apply { mkdirs() }
        return JSONObject().apply {
            put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
            put("SponsorId", "1111111111111111")
            put("EgressRegion", "")
            put("EstablishTunnelTimeoutSeconds", 120)
            put("DataDirectory", dataDir.absolutePath)
            put("ClientVersion", "1")
            put("TunnelProtocol", "")
            put("RemoteServerListURL", "")
            // 0 = Psiphon picks a free port and reports it via onListeningSocksProxyPort.
            put("LocalSocksProxyPort", 0)
            put("RemoteServerListSignaturePublicKey", REMOTE_LIST_KEY)
            put("ServerEntrySignaturePublicKey", SERVER_ENTRY_KEY)
            put("ExchangeObfuscationKey", EXCHANGE_KEY)
            // Psiphon only sends byte counters when this is set.
            put("EmitBytesTransferred", true)
            put("EmitDiagnosticNotices", true)
            put("DeviceRegion", "IR")
            put("ConnectionWorkerPoolSize", 12)
        }.toString()
    }

    override fun bindToDevice(fd: Long) {
        if (!protect(fd.toInt())) throw PsiphonTunnel.Exception("protect(fd=$fd) failed")
    }

    override fun onListeningSocksProxyPort(port: Int) {
        socksPort = port
        maybeReady()
    }

    override fun onConnected() {
        connected = true
        lastActivityAt = SystemClock.elapsedRealtime()
        maybeReady()
    }

    override fun onBytesTransferred(sent: Long, received: Long) {
        if (stopped) return
        lastActivityAt = SystemClock.elapsedRealtime()
        listener.onBytes(sent, received)
    }

    override fun onDiagnosticMessage(message: String) {
        Log.d(TAG, message)
    }

    override fun onExiting() {
        if (stopped) return
        listener.onFailed(
            if (connected) "تونل سایفون قطع شد" else "به سرورهای سایفون وصل نشد"
        )
    }

    private companion object {
        const val TAG = "Psiphon"

        /** DNS-over-TCP query for example.com A, length prefix included. */
        val DNS_PROBE: ByteArray =
            byteArrayOf(0x00, 0x1d) +
                byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) +
                byteArrayOf(0x07) + "example".toByteArray() +
                byteArrayOf(0x03) + "com".toByteArray() +
                byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x01)
        const val REMOTE_LIST_KEY = "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
        const val SERVER_ENTRY_KEY = "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA="
        const val EXCHANGE_KEY = "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU="
    }
}
