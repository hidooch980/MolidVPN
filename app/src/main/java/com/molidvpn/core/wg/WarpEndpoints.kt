package com.molidvpn.core.wg

import java.util.Base64

/**
 * Cloudflare publishes the WARP peer key but not a single address: any of its
 * anycast IPs accepts the same tunnel. Rotating through them turns "the one
 * endpoint we happened to register with is blocked" into a working tunnel.
 */
object WarpEndpoints {

    private const val PEER = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    private val ANYCAST = listOf(
        "162.159.192.1" to 2408,
        "162.159.193.1" to 2408,
        "162.159.195.1" to 2408,
        "162.159.196.1" to 2408,
        "162.159.197.1" to 2408,
        "162.159.200.1" to 2408,
        "162.159.192.1" to 500,
        "162.159.192.1" to 1701,
        "162.159.192.1" to 4500,
        "engage.cloudflareclient.com" to 2408,
    )

    fun forConfig(cfg: WgConfig): List<Pair<String, Int>> {
        val mine = try {
            Base64.getEncoder().encodeToString(cfg.peerPublicKey)
        } catch (e: Exception) {
            ""
        }
        val first = cfg.endpointHost to cfg.endpointPort
        if (mine != PEER) return listOf(first)
        return (listOf(first) + ANYCAST).distinct()
    }
}
