package com.molidvpn.ui.layout

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * One block of the home screen, in the order the user wants it.
 * Everything the sheet shows comes from assets/home_layout.json.
 */
data class HomeSection(
    val id: String,
    val visible: Boolean = true,
    /** Space after this block, in dp. The last block's gap is the sheet's bottom padding. */
    val gap: Int = 8,
    /** Reactor size, 0.4 .. 1.0 of the sheet width. */
    val scale: Float = 0.77f,
)

object HomeLayout {

    const val FILE = "home_layout.json"

    val DEFAULT: List<HomeSection> = listOf(
        HomeSection("modes", gap = 2),
        HomeSection("orb", gap = 10, scale = 0.77f),
        HomeSection("state", gap = 9),
        HomeSection("status_card", gap = 8),
        HomeSection("exits_card", gap = 9),
        HomeSection("metrics", gap = 12),
        HomeSection("hint", gap = 82),
    )

    val KNOWN = DEFAULT.map { it.id }.toSet()

    @Volatile
    private var cached: List<HomeSection>? = null

    /** Reads the sheet recipe once; on any problem the built-in order is used. */
    fun of(context: Context): List<HomeSection> {
        cached?.let { return it }
        val list = runCatching {
            val raw = context.assets.open(FILE).bufferedReader().use { it.readText() }
            parse(raw)
        }.getOrElse {
            Log.w("HomeLayout", "using defaults: " + it.message)
            DEFAULT
        }
        cached = list
        return list
    }

    fun parse(raw: String): List<HomeSection> {
        val array = JSONObject(raw).optJSONArray("sections") ?: return DEFAULT
        val out = mutableListOf<HomeSection>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id", "")
            if (id !in KNOWN) {
                Log.w("HomeLayout", "unknown block ''$id'' ignored")
                continue
            }
            out += HomeSection(
                id = id,
                visible = item.optBoolean("visible", true),
                gap = item.optInt("gap", 8).coerceIn(0, 240),
                scale = item.optDouble("scale", 0.77).toFloat().coerceIn(0.4f, 1f),
            )
        }
        return out.filter { it.visible }.ifEmpty { DEFAULT }
    }
}