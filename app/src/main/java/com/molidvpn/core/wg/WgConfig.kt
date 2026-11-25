package com.molidvpn.core.wg

import java.util.Base64

/** A parsed WireGuard configuration: one interface and one peer. */
class WgConfig(
    val privateKey: ByteArray,
    val peerPublicKey: ByteArray,
    val addresses: List<String>,
    val dns: List<String>,
    val mtu: Int,
    val endpointHost: String,
    val endpointPort: Int,
    val allowedIps: List<String>,
    val keepalive: Int,
) {
    val endpoint: String get() = endpointHost + ":" + endpointPort

    /** Same keys and interface, different server address. */
    fun at(host: String, port: Int): WgConfig = WgConfig(
        privateKey = privateKey,
        peerPublicKey = peerPublicKey,
        addresses = addresses,
        dns = dns,
        mtu = mtu,
        endpointHost = host,
        endpointPort = port,
        allowedIps = allowedIps,
        keepalive = keepalive,
    )

    fun fingerprint(): String {
        val p = WgCrypto.publicKey(privateKey)
        val b = Base64.getEncoder().encodeToString(p)
        return b.take(6) + "…" + b.takeLast(4)
    }

    companion object {
        fun decodeKey(text: String, field: String): ByteArray {
            val clean = text.trim()
            val bytes = try {
                Base64.getDecoder().decode(clean)
            } catch (e: Exception) {
                throw IllegalArgumentException("$field باید Base64 معتبر باشد")
            }
            if (bytes.size != 32) throw IllegalArgumentException("$field باید ۳۲ بایت باشد")
            return bytes
        }

        fun parse(raw: String): WgConfig {
            var section = ""
            val iface = HashMap<String, String>()
            val peer = HashMap<String, String>()

            for (line in raw.lines()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) continue
                if (t.startsWith("[")) {
                    section = t.lowercase()
                    continue
                }
                val eq = t.indexOf('=')
                if (eq <= 0) continue
                val k = t.substring(0, eq).trim().lowercase()
                val v = t.substring(eq + 1).trim()
                when (section) {
                    "[interface]" -> iface.putIfAbsent(k, v)
                    "[peer]" -> peer.putIfAbsent(k, v)
                }
            }

            val priv = iface["privatekey"] ?: throw IllegalArgumentException("PrivateKey پیدا نشد")
            val pub = peer["publickey"] ?: throw IllegalArgumentException("PublicKey پیدا نشد")
            val endpoint = peer["endpoint"] ?: throw IllegalArgumentException("Endpoint پیدا نشد")

            val host: String
            val port: Int
            if (endpoint.startsWith("[")) {
                val close = endpoint.indexOf(']')
                if (close < 0) throw IllegalArgumentException("Endpoint نامعتبر است")
                host = endpoint.substring(1, close)
                port = endpoint.substring(close + 1).removePrefix(":").toIntOrNull()
                    ?: throw IllegalArgumentException("پورت Endpoint نامعتبر است")
            } else {
                val colon = endpoint.lastIndexOf(':')
                if (colon <= 0) throw IllegalArgumentException("Endpoint باید host:port باشد")
                host = endpoint.substring(0, colon)
                port = endpoint.substring(colon + 1).toIntOrNull()
                    ?: throw IllegalArgumentException("پورت Endpoint نامعتبر است")
            }
            if (port !in 1..65535) throw IllegalArgumentException("پورت Endpoint نامعتبر است")

            val addresses = iface["address"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Address پیدا نشد")
            val dns = iface["dns"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val mtu = iface["mtu"]?.toIntOrNull()?.coerceIn(1280, 1500) ?: 1420
            val allowed = peer["allowedips"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.ifEmpty { null } ?: listOf("0.0.0.0/0")
            val keepalive = peer["persistentkeepalive"]?.toIntOrNull()?.coerceIn(0, 65535) ?: 25

            return WgConfig(
                privateKey = decodeKey(priv, "PrivateKey"),
                peerPublicKey = decodeKey(pub, "PublicKey"),
                addresses = addresses,
                dns = dns,
                mtu = mtu,
                endpointHost = host,
                endpointPort = port,
                allowedIps = allowed,
                keepalive = keepalive,
            )
        }

        /** Builds a valid config for the engine self-test: two fresh key pairs. */
        fun testPair(): Pair<WgConfig, WgConfig> {
            val a = WgCrypto.keyPair()
            val b = WgCrypto.keyPair()
            val base = { priv: ByteArray, pub: ByteArray, port: Int ->
                WgConfig(
                    privateKey = priv,
                    peerPublicKey = pub,
                    addresses = listOf("10.66.0.2/32"),
                    dns = listOf("1.1.1.1"),
                    mtu = 1420,
                    endpointHost = "127.0.0.1",
                    endpointPort = port,
                    allowedIps = listOf("0.0.0.0/0"),
                    keepalive = 25,
                )
            }
            return base(a.first, b.second, 51820) to base(b.first, a.second, 51821)
        }
    }
}
