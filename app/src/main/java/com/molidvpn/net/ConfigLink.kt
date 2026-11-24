package com.molidvpn.net

import android.util.Base64
import com.molidvpn.data.Profile
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the configuration links people actually receive: a WireGuard config or
 * `wg://` link, `ss://`, `vless://`, `vmess://`, `trojan://`, or a
 * subscription URL that returns a base64 list of those links.
 */
object ConfigLink {

    class Outcome(
        val wireGuard: String?,
        val profiles: List<Profile>,
        val message: String,
    )

    fun read(text: String): Outcome {
        val input = text.trim()
        if (input.isEmpty()) return Outcome(null, emptyList(), "چیزی وارد نشده")

        // A subscription URL returns the list itself.
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return try {
                val body = fetch(input)
                val links = decode(body) ?: body
                val parsed = links.split('\n').mapNotNull { parseLink(it.trim()) }
                if (parsed.isEmpty()) {
                    Outcome(null, emptyList(), "اشتراک خوانده شد ولی لینکی داخلش نبود")
                } else {
                    Outcome(null, parsed, parsed.size.toString() + " پروفایل از اشتراک ذخیره شد")
                }
            } catch (e: Exception) {
                Outcome(null, emptyList(), "دریافت اشتراک ناموفق بود")
            }
        }

        // A whole wg.conf pasted in.
        if (input.contains("[Interface]")) {
            return Outcome(input, emptyList(), "پیکربندی WireGuard ذخیره شد")
        }

        val single = parseLink(input)
        if (single != null) {
            return if (single.type == "wireguard") {
                Outcome(single.raw, emptyList(), "پیکربندی WireGuard ذخیره شد")
            } else {
                Outcome(null, listOf(single), single.title + " ذخیره شد")
            }
        }

        // Several lines at once (a list pasted from a channel).
        val many = input.split('\n').mapNotNull { parseLink(it.trim()) }
        if (many.isNotEmpty()) {
            val wg = many.firstOrNull { it.type == "wireguard" }
            val rest = many.filter { it.type != "wireguard" }
            return Outcome(wg?.raw, rest, many.size.toString() + " مورد خوانده شد")
        }
        return Outcome(null, emptyList(), "لینک شناخته نشد")
    }

    private fun parseLink(line: String): Profile? {
        val clean = line.trim()
        if (clean.isEmpty() || clean.startsWith("#")) return null
        val body = clean.substringBefore('#')
        val name = clean.substringAfter('#', "").let { if (it.isBlank()) "" else it }

        return when {
            body.startsWith("wg://") || body.startsWith("wireguard://") -> {
                val payload = body.substringAfter("://")
                val conf = decode(payload) ?: decode(urlDecode(payload)) ?: return null
                if (!conf.contains("[Interface]")) return null
                Profile("wireguard", name.ifBlank { "WireGuard" }, endpointOf(conf), conf)
            }
            body.startsWith("ss://") -> shadowSocks(body.substringAfter("ss://"), name)
            body.startsWith("vless://") -> generic("vless", body.substringAfter("vless://"), name)
            body.startsWith("trojan://") -> generic("trojan", body.substringAfter("trojan://"), name)
            body.startsWith("vmess://") -> vmess(body.substringAfter("vmess://"), name)
            else -> null
        }
    }

    private fun shadowSocks(payload: String, name: String): Profile? {
        val core = payload.substringBefore('?')
        val plain = if (core.contains('@')) {
            val user = core.substringBeforeLast('@')
            val rest = core.substringAfterLast('@')
            val decoded = decode(user) ?: user
            decoded + "@" + rest
        } else {
            decode(core) ?: return null
        }
        val creds = plain.substringBeforeLast('@')
        val hostPort = plain.substringAfterLast('@')
        if (!creds.contains(':') || !hostPort.contains(':')) return null
        return Profile(
            type = "shadowsocks",
            title = name.ifBlank { "Shadowsocks " + hostPort },
            endpoint = hostPort,
            raw = payload.let { "ss://" + it },
        )
    }

    private fun generic(type: String, payload: String, name: String): Profile? {
        val authority = payload.substringBefore('?').substringBefore('/')
        val hostPort = if (authority.contains('@')) authority.substringAfterLast('@') else authority
        if (!hostPort.contains(':')) return null
        return Profile(type, name.ifBlank { type.uppercase() + " " + hostPort }, hostPort, type + "://" + payload)
    }

    private fun vmess(payload: String, name: String): Profile? {
        val json = decode(payload) ?: return null
        return try {
            val o = JSONObject(json)
            val host = o.optString("add")
            val port = o.optInt("port")
            if (host.isBlank() || port == 0) return null
            Profile("vmess", name.ifBlank { o.optString("ps", "VMess " + host) }, host + ":" + port, "vmess://" + payload)
        } catch (e: Exception) {
            null
        }
    }

    private fun endpointOf(conf: String): String =
        conf.lineSequence().firstOrNull { it.trim().startsWith("Endpoint") }
            ?.substringAfter('=')?.trim().orEmpty()

    private fun decode(value: String): String? {
        val clean = value.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        if (clean.isEmpty()) return null
        val pad = (4 - clean.length % 4) % 4
        return try {
            val bytes = Base64.decode(clean + "=".repeat(pad), Base64.DEFAULT)
            val text = String(bytes, Charsets.UTF_8)
            if (text.all { it == '\u0000' }) null else text
        } catch (e: Exception) {
            null
        }
    }

    private fun urlDecode(value: String): String =
        try { java.net.URLDecoder.decode(value, "UTF-8") } catch (e: Exception) { value }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MolidVPN")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
