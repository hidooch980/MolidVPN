package com.molidvpn.net

import com.molidvpn.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Round-trip probe for a WireGuard endpoint: the TCP handshake time is a
 * good proxy for reachability, and a refused connection still gives us RTT.
 */
object Net {
    /** TCP handshake to Google's edge; a refused/blocked connect still measures RTT. */
    suspend fun google(timeoutMs: Int = 1600): Int? = withContext(Dispatchers.IO) {
        val addr = try {
            InetSocketAddress(InetAddress.getByName("www.google.com"), 443)
        } catch (_: Exception) {
            return@withContext null
        }
        val t0 = System.nanoTime()
        try {
            Socket().use { it.connect(addr, timeoutMs) }
            elapsed(t0)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun ping(ip: String, port: Int = 51820, timeoutMs: Int = 1400): Int? =
        withContext(Dispatchers.IO) {
            val t0 = System.nanoTime()
            try {
                Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
                elapsed(t0)
            } catch (e: ConnectException) {
                elapsed(t0)
            } catch (e: Exception) {
                null
            }
        }

    private fun elapsed(t0: Long): Int =
        ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceIn(1, 4000)
}

/**
 * One honest number for the home screen: the measured TCP round-trip to Google.
 * No seed values, no "average of nothing" — a failed probe is simply [ms] == null.
 */
object GooglePing {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var ms by mutableStateOf<Int?>(null)
        private set
    var checkedAt by mutableStateOf(0L)
        private set
    private var running = false

    fun measure(times: Int = 3) {
        if (running) return
        running = true
        scope.launch {
            val samples = (1..times).mapNotNull { Net.google() }
            ms = samples.minOrNull()
            checkedAt = System.currentTimeMillis()
            running = false
        }
    }
}

/** Live ping table plus the smart selector that keeps the best path on top. */
object Smart {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val pings = mutableStateMapOf<String, Int>()
    val testing = mutableStateListOf<String>()

    var bestId by mutableStateOf<String?>(null)
        private set
    var lastTestAt by mutableStateOf(0L)
        private set

    fun pingOf(server: Server): Int? = pings[server.id]

    fun test(server: Server, onResult: ((Int) -> Unit)? = null) {
        if (testing.contains(server.id)) return
        testing.add(server.id)
        scope.launch {
            val raw = Net.ping(server.ip)
            testing.remove(server.id)
            lastTestAt = System.currentTimeMillis()
            if (raw == null) {
                // Unreachable is a result too: drop the old number instead of showing it forever.
                pings.remove(server.id)
                if (onResult != null) withContext(Dispatchers.Main) { onResult(-1) }
            } else {
                val ms = smooth(server, raw)
                if (onResult != null) withContext(Dispatchers.Main) { onResult(ms) }
            }
        }
    }

    fun testAll(servers: List<Server>, onDone: ((Server?) -> Unit)? = null) {
        scope.launch {
            servers.filterNot { testing.contains(it.id) }.forEach { testing.add(it.id) }
            coroutineScope {
                servers.map { s ->
                    async {
                        val raw = Net.ping(s.ip)
                        if (raw == null) pings.remove(s.id) else smooth(s, raw)
                    }
                }.awaitAll()
            }
            testing.clear()
            lastTestAt = System.currentTimeMillis()
            val best = servers.filter { pings[it.id] != null }.minByOrNull { pings[it.id] ?: Int.MAX_VALUE }
            bestId = best?.id
            if (onDone != null) withContext(Dispatchers.Main) { onDone(best) }
        }
    }

    /** Exponential smoothing keeps the displayed latency from jumping around. */
    private fun smooth(server: Server, raw: Int): Int {
        val prev = pings[server.id]
        val next = if (prev == null) raw else (prev * 0.6f + raw * 0.4f).toInt().coerceAtLeast(1)
        pings[server.id] = next
        return next
    }
}
