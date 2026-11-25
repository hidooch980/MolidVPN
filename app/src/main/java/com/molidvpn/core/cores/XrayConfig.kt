package com.molidvpn.core.cores

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Turns a share link (ss, vless, vmess, trojan) into an Xray config that exposes
 * a local SOCKS5 inbound on [port]. Returns null when the link is not supported.
 */
object XrayConfig {

    fun build(link: String, port: Int): String? {
        val outbound = try {
            when {
                link.startsWith("vless://") -> vless(link)
                link.startsWith("trojan://") -> trojan(link)
                link.startsWith("vmess://") -> vmess(link)
                link.startsWith("ss://") -> shadowsocks(link)
                else -> null
            }
        } catch (_: Exception) {
            null
        } ?: return null

        val inbound = JSONObject()
            .put("listen", "127.0.0.1")
            .put("port", port)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", false))
        val direct = JSONObject().put("protocol", "freedom").put("tag", "direct")
        val dns = JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound).put(direct))
            .toString()
    }

    private fun vless(link: String): JSONObject {
        val (userInfo, host, port, q) = split(link.removePrefix("vless://"))
        val user = JSONObject().put("id", userInfo).put("encryption", "none")
        q["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
        val server = JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user))
        return JSONObject()
            .put("protocol", "vless")
            .put("tag", "proxy")
            .put("settings", JSONObject().put("vnext", JSONArray().put(server)))
            .put("streamSettings", stream(q, host))
    }

    private fun trojan(link: String): JSONObject {
        val (password, host, port, q) = split(link.removePrefix("trojan://"))
        val server = JSONObject().put("address", host).put("port", port).put("password", password)
        val security = q["security"] ?: "tls"
        return JSONObject()
            .put("protocol", "trojan")
            .put("tag", "proxy")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put("streamSettings", stream(q + ("security" to security), host))
    }

    private fun vmess(link: String): JSONObject {
        val json = JSONObject(decodeB64(link.removePrefix("vmess://")))
        val user = JSONObject()
            .put("id", json.optString("id"))
            .put("alterId", json.optString("aid", "0").toIntOrNull() ?: 0)
            .put("security", "auto")
        val server = JSONObject()
            .put("address", json.optString("add"))
            .put("port", json.optString("port").toIntOrNull() ?: json.optInt("port"))
            .put("users", JSONArray().put(user))
        val q = mapOf(
            "type" to json.optString("net", "tcp"),
            "host" to json.optString("host"),
            "path" to json.optString("path"),
            "security" to if (json.optString("tls") == "tls") "tls" else "none",
            "sni" to json.optString("sni"),
        )
        return JSONObject()
            .put("protocol", "vmess")
            .put("tag", "proxy")
            .put("settings", JSONObject().put("vnext", JSONArray().put(server)))
            .put("streamSettings", stream(q, json.optString("add")))
    }

    private fun shadowsocks(link: String): JSONObject {
        val body = link.removePrefix("ss://").substringBefore('#')
        val (method, password, host, port) = if ('@' in body) {
            val info = body.substringAfterLast('@')
            val userPart = body.substringBeforeLast('@')
            val decoded = if (':' in userPart) userPart else decodeB64(userPart)
            val hostPort = info.substringBeforeLast(':')
            val creds = decoded.split(':', limit = 2)
            listOf(creds[0], creds[1], hostPort, info.substringAfterLast(':'))
        } else {
            val decoded = decodeB64(body)
            val creds = decoded.substringBeforeLast('@').split(':', limit = 2)
            val hostPort = decoded.substringAfterLast('@')
            listOf(creds[0], creds[1], hostPort.substringBeforeLast(':'), hostPort.substringAfterLast(':'))
        }
        val server = JSONObject()
            .put("address", host)
            .put("port", port.toInt())
            .put("method", method)
            .put("password", password)
        return JSONObject()
            .put("protocol", "shadowsocks")
            .put("tag", "proxy")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
    }

    /** Builds streamSettings from common query keys: type, security, sni, host, path, fp, pbk, sid, serviceName. */
    private fun stream(q: Map<String, String>, host: String): JSONObject {
        val network = q["type"]?.takeIf { it.isNotBlank() } ?: "tcp"
        val s = JSONObject().put("network", network)
        when (network) {
            "ws" -> s.put("wsSettings", JSONObject()
                .put("path", q["path"]?.ifBlank { "/" } ?: "/")
                .put("headers", JSONObject().put("Host", q["host"] ?: host)))
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", q["serviceName"] ?: ""))
        }
        when (q["security"]) {
            "tls" -> s.put("security", "tls").put("tlsSettings", JSONObject()
                .put("serverName", q["sni"]?.ifBlank { null } ?: host)
                .put("fingerprint", q["fp"] ?: "chrome"))
            "reality" -> s.put("security", "reality").put("realitySettings", JSONObject()
                .put("serverName", q["sni"] ?: host)
                .put("fingerprint", q["fp"] ?: "chrome")
                .put("publicKey", q["pbk"] ?: "")
                .put("shortId", q["sid"] ?: "")
                .put("spiderX", "/"))
        }
        return s
    }

    /** Splits `secret@host:port?query#name` into its parts. */
    private data class Parts(val secret: String, val host: String, val port: Int, val query: Map<String, String>)

    private fun split(rest: String): Parts {
        val noTag = rest.substringBefore('#')
        val beforeQuery = noTag.substringBefore('?')
        val secret = beforeQuery.substringBeforeLast('@')
        val hostPort = beforeQuery.substringAfterLast('@')
        val query = noTag.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { part ->
                val k = part.substringBefore('=')
                val v = URLDecoder.decode(part.substringAfter('='), "UTF-8")
                k to v
            }
        val host = hostPort.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        return Parts(secret, host, hostPort.substringAfterLast(':').toInt(), query)
    }

    private fun decodeB64(text: String): String {
        val clean = text.trim().replace('-', '+').replace('_', '/')
        return String(Base64.decode(clean, Base64.DEFAULT or Base64.NO_WRAP))
    }
}
