package com.molidvpn.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Every switch in the app lives here. Nothing is manual by design:
 * the smart engine owns selection, failover and testing.
 */
class SettingsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("molid", Context.MODE_PRIVATE)

    var smartSelect by mutableStateOf(p.getBoolean("smart_select", true)); private set
    var autoFailover by mutableStateOf(p.getBoolean("auto_failover", true)); private set
    var autoPing by mutableStateOf(p.getBoolean("auto_ping", true)); private set
    var smartUpdate by mutableStateOf(p.getBoolean("smart_update", true)); private set
    var killSwitch by mutableStateOf(p.getBoolean("kill_switch", true)); private set
    var secureDns by mutableStateOf(p.getBoolean("secure_dns", true)); private set

    var wgConfig by mutableStateOf(p.getString("wg_config", "") ?: ""); private set

    fun updateWgConfig(v: String) {
        wgConfig = v
        p.edit().putString("wg_config", v).apply()
    }

    var selectedId: String
        get() = p.getString("selected", "ams") ?: "ams"
        set(v) { p.edit().putString("selected", v).apply() }

    fun updateSmartSelect(v: Boolean) { smartSelect = v; p.edit().putBoolean("smart_select", v).apply() }
    fun updateFailover(v: Boolean) { autoFailover = v; p.edit().putBoolean("auto_failover", v).apply() }
    fun updateAutoPing(v: Boolean) { autoPing = v; p.edit().putBoolean("auto_ping", v).apply() }
    fun updateSmartUpdate(v: Boolean) { smartUpdate = v; p.edit().putBoolean("smart_update", v).apply() }
    fun updateKillSwitch(v: Boolean) { killSwitch = v; p.edit().putBoolean("kill_switch", v).apply() }
    fun updateSecureDns(v: Boolean) { secureDns = v; p.edit().putBoolean("secure_dns", v).apply() }
}
