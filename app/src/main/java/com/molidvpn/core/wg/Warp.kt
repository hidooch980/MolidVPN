package com.molidvpn.core.wg

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A free Cloudflare WARP profile: no account, no link and no server of ours.
 *
 * The registration call hands back the tunnel address for the device; the
 * answer is written as an ordinary WireGuard configuration so the existing
 * engine can connect to it unchanged.
 */
object Warp {
    private const val API = "https://api.cloudflareclient.com/v0a2158/reg"

    /** Cloudflare's public WARP peer key, the same for every free device. */
    private const val PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    /** WARP listens on several anycast addresses; the first one is the usual entry. */
    private const val ENDPOINT = "162.159.192.9:2408"

    /** Registers a fresh device and returns a ready to use WireGuard profile. */
    fun profile(): String {
        val (privateKey, publicKey) = WgCrypto.keyPair()
        val private64 = Base64.getEncoder().encodeToString(privateKey)

        val body = JSONObject()
            .put("key", Base64.getEncoder().encodeToString(publicKey))
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", timestamp())
            .put("model", "PC")
            .put("type", "Android")
            .put("locale", "en_US")
            .toString()

        val conn = URL(API).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("CF-Client-Version", "a-6.30-2158")
            conn.setRequestProperty("User-Agent", "okhttp/3.12.1")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("WARP " + conn.responseCode)
            }
            build(conn.inputStream.bufferedReader().readText(), private64)
        } finally {
            conn.disconnect()
        }
    }

    private fun build(json: String, private64: String): String {
        val config = JSONObject(json).getJSONObject("config")
        val addresses = config.getJSONObject("interface").getJSONObject("addresses")
        val v4 = addresses.optString("v4").ifBlank { "172.16.0.2" }

        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = " + private64)
            appendLine("Address = " + v4 + "/32")
            appendLine("MTU = 1280")
            appendLine("DNS = 1.1.1.1")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = " + PEER_KEY)
            appendLine("Endpoint = " + ENDPOINT)
            appendLine("AllowedIPs = 0.0.0.0/0")
            appendLine("PersistentKeepalive = 25")
        }
    }

    private fun timestamp(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
