package com.molidvpn.core.wg

/**
 * Tuning presets for the WireGuard engine.
 *
 * These are not separate protocols: they only change the interface MTU, the
 * persistent-keepalive interval and the UDP socket buffer, all of which are
 * real knobs the engine honours. They are never advertised as "faster" on
 * their own; the app shows exactly what each one changes.
 */
object TunnelProfiles {
    data class Profile(
        val id: String,
        val title: String,
        val note: String,
        val mtu: Int,
        val keepalive: Int,
        val bufferBytes: Int,
    )

    val all = listOf(
        Profile("standard", "استاندارد", "MTU ۱۴۲۰ · keepalive ۲۵ ثانیه", 1420, 25, 0),
        Profile("gaming", "گیمینگ پلاس", "MTU ۱۳۸۰ · keepalive ۱۰ ثانیه", 1380, 10, 0),
        Profile("stream", "استریم و دانلود", "MTU ۱۴۲۰ · بافر ۴ مگابایتی", 1420, 25, 4 * 1024 * 1024),
        Profile("anti", "ضداختلال", "MTU ۱۲۸۰ · keepalive ۵ ثانیه", 1280, 5, 0),
    )

    fun byId(id: String): Profile = all.firstOrNull { it.id == id } ?: all[0]
}
