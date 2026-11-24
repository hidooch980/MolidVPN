package com.molidvpn.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class VpnStatus { IDLE, CONNECTING, CONNECTED, FAILED }

object VpnState {
    var status by mutableStateOf(VpnStatus.IDLE)
    var startedAt by mutableLongStateOf(0L)
    var rxMbps by mutableStateOf(0.0)
    var txMbps by mutableStateOf(0.0)

    /** Seconds since the last successful handshake, 0 while none happened yet. */
    var handshakeAge by mutableIntStateOf(0)
    var handshakes by mutableIntStateOf(0)
    var rxBytes by mutableLongStateOf(0L)
    var txBytes by mutableLongStateOf(0L)
    var detail by mutableStateOf("")

    val connected: Boolean get() = status == VpnStatus.CONNECTED
    /** True while the tunnel is coming up but the handshake has not completed. */
    val handshaking: Boolean get() = status == VpnStatus.CONNECTING

    fun reset() {
        status = VpnStatus.IDLE
        startedAt = 0L
        rxMbps = 0.0
        txMbps = 0.0
        handshakeAge = 0
        handshakes = 0
        rxBytes = 0L
        txBytes = 0L
        detail = ""
    }
}

object VpnController {
    fun prepare(ctx: Context): Intent? = VpnService.prepare(ctx)

    fun start(ctx: Context) {
        ctx.startService(Intent(ctx, TunnelService::class.java).setAction(TunnelService.ACTION_START))
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, TunnelService::class.java).setAction(TunnelService.ACTION_STOP))
    }
}
