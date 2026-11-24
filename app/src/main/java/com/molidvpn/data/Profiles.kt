package com.molidvpn.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** A configuration that came in from a share link or a subscription. */
class Profile(
    val type: String,
    val title: String,
    val endpoint: String,
    val raw: String,
)

/**
 * Imported configurations live next to the settings so they survive a restart.
 * Only WireGuard can carry traffic today; the rest wait for the second engine.
 */
class ProfileStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("molid", Context.MODE_PRIVATE)

    var all by mutableStateOf(read()); private set

    fun add(items: List<Profile>) {
        if (items.isEmpty()) return
        val merged = LinkedHashMap<String, Profile>()
        all.forEach { merged[it.raw] = it }
        items.forEach { merged[it.raw] = it }
        val list = merged.values.toList()
        all = list
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("type", it.type)
                    .put("title", it.title)
                    .put("endpoint", it.endpoint)
                    .put("raw", it.raw)
            )
        }
        p.edit().putString("imported", array.toString()).apply()
    }

    private fun read(): List<Profile> {
        val raw = p.getString("imported", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Profile(
                    type = o.optString("type"),
                    title = o.optString("title"),
                    endpoint = o.optString("endpoint"),
                    raw = o.optString("raw"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
