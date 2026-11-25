package com.molidvpn.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The address the outside world sees for this device. It is read over plain
 * HTTPS, so behind the tunnel it reports the exit node and not the subscriber
 * line: the same card then answers "where do I appear from?" in both states.
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