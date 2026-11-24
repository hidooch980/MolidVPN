package com.molidvpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.molidvpn.ui.icons.MolidIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molidvpn.data.Server
import com.molidvpn.data.Servers
import com.molidvpn.data.SettingsStore
import com.molidvpn.net.Smart
import com.molidvpn.ui.components.ToastCard
import com.molidvpn.ui.screens.ConnectScreen
import com.molidvpn.ui.screens.RoutesScreen
import com.molidvpn.ui.screens.SettingsScreen
import com.molidvpn.ui.theme.M
import com.molidvpn.ui.theme.MolidFont
import kotlinx.coroutines.delay

@Composable
fun MolidRoot(
    settings: SettingsStore,
    connected: Boolean,
    connecting: Boolean,
    engineDetail: String,
    handshakeAge: Int,
    startedAt: Long,
    selected: Server,
    ping: Int?,
    rx: Double,
    tx: Double,
    toast: String?,
    onToast: (String) -> Unit,
    onToggle: () -> Unit,
    onSelect: (Server) -> Unit,
    onCheckUpdate: () -> Unit,
    onEditConfig: () -> Unit,
    onSelfTest: () -> Unit,
    onWarp: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    val selectLatest by rememberUpdatedState(onSelect)
    val toastLatest by rememberUpdatedState(onToast)

    // The engine keeps the table warm and moves the tunnel to the best path.
    LaunchedEffect(settings.autoPing) {
        if (!settings.autoPing) return@LaunchedEffect
        while (true) {
            Smart.testAll(Servers.all) { best ->
                if (settings.smartSelect && best != null && !connected) selectLatest(best)
            }
            delay(60_000)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0C1128), Color(0xFF08090F), M.Ink)
                )
            )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(430.dp)
                .background(
                    Brush.radialGradient(
                        listOf(M.Violet.copy(alpha = 0.22f), Color.Transparent),
                    )
                )
        )

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar()
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ConnectScreen(
                        connected = connected,
                        connecting = connecting,
                        engineDetail = engineDetail,
                        handshakeAge = handshakeAge,
                        startedAt = startedAt,
                        server = selected,
                        ping = ping,
                        rx = rx,
                        tx = tx,
                        servers = Servers.all,
                        mode = settings.mode,
                        onMode = { m ->
                            settings.updateMode(m)
                            when (m) {
                                "safe" -> {
                                    settings.updateKillSwitch(true)
                                    settings.updateSecureDns(true)
                                    settings.updateFailover(false)
                                    toastLatest("حالت امن: فقط مسیر انتخابی")
                                }
                                "free" -> {
                                    if (settings.wgConfig.contains("162.159.192.")) {
                                        toastLatest("حالت رایگان WARP فعال است")
                                    } else {
                                        toastLatest("در حال ساخت پیکربندی رایگان…")
                                        onWarp()
                                    }
                                }
                                "psiphon" -> toastLatest("سایفون: اپ جداگانه را نصب کن و آن‌جا وصل شو")
                                "tor" -> toastLatest("تور: Orbot را نصب کن و آن‌جا وصل شو")
                                else -> {
                                    settings.updateSmartSelect(true)
                                    settings.updateAutoPing(true)
                                    settings.updateFailover(true)
                                    toastLatest("حالت هوشمند فعال شد")
                                }
                            }
                        },
                        onOpenLink = onOpenLink,
                        onToggle = onToggle,
                        onSelect = { s -> onSelect(s) },
                        onToast = toastLatest,
                    )
                    1 -> RoutesScreen(
                        servers = Servers.all,
                        selected = selected,
                        onSelect = { s ->
                            onSelect(s)
                            toastLatest("سرور فعال: " + s.title)
                        },
                        onTest = { s ->
                            Smart.test(s) { ms ->
                                toastLatest("پینگ " + s.title + " — " + faNum(ms) + " میلی‌ثانیه")
                            }
                        },
                        onTestAll = {
                            Smart.testAll(Servers.all) { best ->
                                toastLatest(
                                    if (best != null) "سریع‌ترین مسیر: " + best.title
                                    else "همهٔ مسیرها تست شدند"
                                )
                            }
                        },
                    )
                    else -> SettingsScreen(
                        settings = settings,
                        server = selected,
                        onCheckUpdate = onCheckUpdate,
                        onToast = toastLatest,
                        onEditConfig = onEditConfig,
                        onSelfTest = onSelfTest,
                        onWarp = onWarp,
                        onOpenLink = onOpenLink,
                    )
                }
            }
            TabBar(tab) { tab = it }
        }

        AnimatedVisibility(
            visible = toast != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 74.dp),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut(),
        ) {
            ToastCard(toast ?: "")
        }
    }
}

@Composable
private fun TopBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    Brush.linearGradient(listOf(M.Violet, M.Cyan.copy(alpha = 0.85f)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MolidIcons.ShieldCheck, null, Modifier.size(15.dp), tint = Color(0xFF070A12))
        }
        Spacer(Modifier.width(7.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "MOLID",
                    fontSize = 13.sp,
                    fontFamily = MolidFont,
                    fontWeight = FontWeight.Bold,
                    color = M.Text,
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    "VPN",
                    fontSize = 13.sp,
                    fontFamily = MolidFont,
                    fontWeight = FontWeight.Normal,
                    color = M.Muted,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(
            MolidIcons.Help,
            contentDescription = "راهنما",
            Modifier.size(17.dp),
            tint = M.Muted,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "م",
                fontSize = 11.sp,
                fontFamily = MolidFont,
                fontWeight = FontWeight.Bold,
                color = M.Text,
            )
        }
    }
}

@Composable
private fun TabBar(current: Int, onTab: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 10.dp, top = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xF20A0C14))
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem("اتصال", MolidIcons.Shield, current == 0) { onTab(0) }
            TabItem("مسیرها", MolidIcons.Globe, current == 1) { onTab(1) }
            TabItem("تنظیمات", MolidIcons.Sliders, current == 2) { onTab(2) }
        }
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            Modifier.size(14.dp),
            tint = if (active) M.Cyan else M.Muted2,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 9.5.sp,
            fontFamily = MolidFont,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) M.Text else M.Muted2,
        )
    }
}
