package com.molidvpn.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.molidvpn.core.cores.SocksCore
import java.net.HttpURLConnection
import java.net.URL

/**
 * The address of this device as the network sees it, read over plain HTTPS.
 * The VPN app own sockets bypass its own tunnel, so this is always the local
 * line; [ExitIp] is the one that reports what the tunnel exposes.
 */
object MyIp {
    private val IP = Regex("^[0-9a-fA-F:.]{7,45}$")
    private val sources = listOf(
        "https://api.ipify.org",
        "https://ipinfo.io/ip",
        "https://ifconfig.me/ip",
    )

    var value by mutableStateOf<String?>(null)
        private set
    var updatedAt by mutableLongStateOf(0L)
        private set

    suspend fun refresh() = withContext(Dispatchers.IO) {
        for (u in sources) {
            val text = try { fetch(u) } catch (_: Exception) { null }
            if (text != null && IP.matches(text)) {
                value = text
                updatedAt = System.currentTimeMillis()
                return@withContext
            }
        }
    }

    private fun fetch(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "MolidVPN")
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }.trim()
        } finally {
            conn.disconnect()
        }
    }
}
/** The address the tunnel really exposes, read through the engine own SOCKS port. */
object ExitIp {
    var value by mutableStateOf<String?>(null)
        private set

    suspend fun refresh(port: Int) = withContext(Dispatchers.IO) {
        val ip = SocksCore.ipViaSocks(port)
        if (ip != null) value = ip
    }

    fun clear() {
        value = null
    }
}
