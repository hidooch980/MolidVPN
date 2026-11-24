package com.molidvpn.ui

/** Latin digits to Persian digits, so numbers read naturally in the RTL UI. */
fun faNum(value: Any?): String {
    if (value == null) return "—"
    val s = value.toString()
    val sb = StringBuilder()
    for (ch in s) {
        sb.append(if (ch in '0'..'9') ('۰' + (ch - '0')) else ch)
    }
    return sb.toString()
}

/** Isolates a numeric value with its unit so RTL text keeps "42 ms" order. */
fun ltr(s: String): String = "\u2066" + s + "\u2069"

fun fmtTimer(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    val pad = { v: Long -> if (v < 10) "0" + v else v.toString() }
    return faNum(pad(h) + ":" + pad(m) + ":" + pad(s))
}

/** Same clock in Latin digits for the reactor core, where it reads cleaner. */
fun fmtClock(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    val pad = { v: Long -> if (v < 10) "0" + v else v.toString() }
    return pad(h) + ":" + pad(m) + ":" + pad(s)
}

fun pingText(ms: Int?): String = if (ms == null) "···" else faNum(ms) + " ms"

fun pingShort(ms: Int?): String = if (ms == null) "—" else faNum(ms)

/** 0f..1f quality used by the little signal bars. */
fun quality(ms: Int?): Float = when {
    ms == null -> 0f
    ms < 100 -> 1f
    ms < 160 -> 0.8f
    ms < 240 -> 0.6f
    ms < 340 -> 0.4f
    else -> 0.2f
}
