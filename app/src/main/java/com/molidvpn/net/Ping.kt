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
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Round-trip probe for a WireGuard endpoint: the TCP handshake time is a
 * good proxy for reachability, and a refused connection still gives us RTT.
 */
object Net {
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
            val ms = smooth(server, Net.ping(server.ip) ?: server.seedPing)
            testing.remove(server.id)
            lastTestAt = System.currentTimeMillis()
            if (onResult != null) withContext(Dispatchers.Main) { onResult(ms) }
        }
    }

    fun testAll(servers: List<Server>, onDone: ((Server?) -> Unit)? = null) {
        scope.launch {
            servers.filterNot { testing.contains(it.id) }.forEach { testing.add(it.id) }
            coroutineScope {
                servers.map { s ->
                    async {
                        smooth(s, Net.ping(s.ip) ?: s.seedPing)
                    }
                }.awaitAll()
            }
            testing.clear()
            lastTestAt = System.currentTimeMillis()
            val best = servers.minByOrNull { pings[it.id] ?: it.seedPing }
            // Hysteresis: keep the current path unless the new one is clearly faster,
            // so the tunnel does not hop between equals every minute.
            val current = bestId?.let { id -> servers.firstOrNull { it.id == id } }
            val chosen = if (current != null && best != null && current.id != best.id) {
                val c = pings[current.id] ?: current.seedPing
                val b = pings[best.id] ?: best.seedPing
                if (b < c * 0.85f) best else current
            } else {
                best
            }
            bestId = chosen?.id
            if (onDone != null) withContext(Dispatchers.Main) { onDone(chosen) }
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
