package com.molidvpn.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.molidvpn.ui.icons.MolidIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molidvpn.data.Server
import com.molidvpn.net.Smart
import com.molidvpn.ui.components.FlagChip
import com.molidvpn.ui.components.GlassCard
import com.molidvpn.ui.components.OrbitReactor
import com.molidvpn.ui.components.Pill
import com.molidvpn.ui.faNum
import com.molidvpn.ui.layout.HomeLayout
import com.molidvpn.ui.ltr
import com.molidvpn.ui.fmtClock
import com.molidvpn.ui.fmtTimer
import com.molidvpn.ui.pingShort
import com.molidvpn.ui.theme.M
import com.molidvpn.ui.theme.MolidFont
import kotlinx.coroutines.delay

@Composable
fun ConnectScreen(
    connected: Boolean,
    connecting: Boolean,
    engineDetail: String,
    handshakeAge: Int,
    startedAt: Long,
    server: Server,
    ping: Int?,
    rx: Double,
    tx: Double,
    servers: List<Server>,
    onToggle: () -> Unit,
    onSelect: (Server) -> Unit,
    onToast: (String) -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connected, startedAt) {
        while (connected) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val live = connected || connecting
    val secs = if (connected && startedAt > 0) ((now - startedAt) / 1000).coerceAtLeast(0) else 0
    val timer = fmtTimer(secs)
    val clock = fmtClock(secs)
    var open by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()

    // Opening a drawer nudges the sheet just enough into view, nothing more.

    val context = LocalContext.current
    val sections = remember(context) { HomeLayout.of(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
    ) {
        sections.forEach { section ->
            when (section.id) {
                "modes" -> ModesSection(connected, live)
                "orb" -> OrbSection(live, connected, connecting, clock, onToggle, section.scale)
                "state" -> StateSection(connected, connecting, server)
                "status_card" -> StatusCardSection(
                    connected = connected,
                    connecting = connecting,
                    engineDetail = engineDetail,
                    handshakeAge = handshakeAge,
                    server = server,
                    ping = ping,
                    timer = timer,
                    rx = rx,
                    tx = tx,
                    open = open == 1,
                    onToggle = { open = if (open == 1) 0 else 1 },
                )
                "exits_card" -> ExitsCardSection(
                    server = server,
                    servers = servers,
                    open = open == 2,
                    onToggle = { open = if (open == 2) 0 else 2 },
                    onClose = { open = 0 },
                    onSelect = onSelect,
                    onToast = onToast,
                )
                "metrics" -> MetricsSection(connected, ping, rx, tx)
                "hint" -> HintSection(connected, connecting, handshakeAge, engineDetail)
            }
            Spacer(Modifier.height(section.gap.dp))
        }
    }
}

@Composable
private fun ModesSection(connected: Boolean, live: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(
            text = "اتصال امن",
            dot = when {
                connected -> M.Mint
                live -> M.Amber
                else -> M.Muted2
            },
            tint = if (live) M.TextDim else M.Muted,
        )
        Spacer(Modifier.width(6.dp))
        Pill(
            text = "انتخاب هوشمند",
            icon = MolidIcons.Sparkle,
            tint = M.Cyan.copy(alpha = 0.9f),
        )
    }
}

@Composable
private fun OrbSection(
    live: Boolean,
    connected: Boolean,
    connecting: Boolean,
    clock: String,
    onToggle: () -> Unit,
    scale: Float,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        OrbitReactor(
            connected = live,
            timer = clock,
            action = when {
                connected -> "قطع اتصال"
                connecting -> "لغو"
                else -> "اتصال"
            },
            onToggle = onToggle,
            modifier = Modifier.fillMaxWidth(scale).aspectRatio(1f),
        )
    }
}

@Composable
private fun StateSection(connected: Boolean, connecting: Boolean, server: Server) {
    AnimatedContent(
        targetState = when {
            connected -> 2
            connecting -> 1
            else -> 0
        },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "state",
    ) { on ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (on) {
                    2 -> "متصل"
                    1 -> "در حال اتصال"
                    else -> "قطع"
                },
                fontSize = if (on == 1) 24.sp else 30.sp,
                fontFamily = MolidFont,
                fontWeight = FontWeight.Bold,
                color = when (on) {
                    2 -> M.Text
                    1 -> M.Amber
                    else -> M.Muted
                },
                lineHeight = 38.sp,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagChip(server.flag, 18.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    server.title,
                    fontSize = 13.sp,
                    color = if (on != 0) M.TextDim else M.Muted,
                    fontFamily = MolidFont,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(5.dp))
                Text("·", fontSize = 9.sp, color = M.Muted2)
                Spacer(Modifier.width(5.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        server.ip,
                        fontSize = 10.5.sp,
                        color = M.Muted2,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCardSection(
    connected: Boolean,
    connecting: Boolean,
    engineDetail: String,
    handshakeAge: Int,
    server: Server,
    ping: Int?,
    timer: String,
    rx: Double,
    tx: Double,
    open: Boolean,
    onToggle: () -> Unit,
) {
    DrawerCard(
        icon = MolidIcons.ShieldCheck,
        tint = if (connected) M.Mint else M.Muted,
        title = "وضعیت اتصال",
        summary = when {
            connected -> "متصل · " + timer
            connecting -> "در حال دست‌دادن با سرور…"
            else -> if (engineDetail.isNotEmpty()) engineDetail else "قطع · آمادهٔ اتصال"
        },
        open = open,
        onToggle = onToggle,
    ) {
        StatusBody(connected, connecting, handshakeAge, server, ping, timer, rx, tx)
    }
}

@Composable
private fun ExitsCardSection(
    server: Server,
    servers: List<Server>,
    open: Boolean,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onSelect: (Server) -> Unit,
    onToast: (String) -> Unit,
) {
    DrawerCard(
        icon = MolidIcons.Globe,
        tint = M.Cyan,
        title = "مکان خروجی",
        summary = server.flag + "  " + server.title + "  ·  " +
            if (Smart.pings[server.id] != null) ltr(faNum(Smart.pings[server.id]) + " ms") else "در حال سنجش",
        open = open,
        onToggle = onToggle,
    ) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 6.dp)) {
            servers.forEach { s ->
                ExitRow(
                    server = s,
                    selected = s.id == server.id,
                    ping = Smart.pings[s.id],
                    testing = Smart.testing.contains(s.id),
                    onClick = {
                        onSelect(s)
                        onClose()
                        onToast("سرور فعال: " + s.title)
                    },
                )
            }
            Text(
                "فهرست خودکار هر ۶۰ ثانیه تست می‌شود؛ انتخاب هم خودکار است",
                fontSize = 7.5.sp,
                fontFamily = MolidFont,
                color = M.Muted2,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun MetricsSection(connected: Boolean, ping: Int?, rx: Double, tx: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Metric(
            Modifier.weight(1f),
            value = if (connected) faNum(ping ?: 0) else "—",
            unit = if (connected) "ms" else "",
            label = "پینگ",
            accent = M.Cyan,
        )
        Metric(
            Modifier.weight(1f),
            value = if (connected) String.format(java.util.Locale.US, "%.1f", rx) else "—",
            unit = if (connected) "Mbps" else "",
            label = "دانلود",
            accent = M.Mint,
        )
        Metric(
            Modifier.weight(1f),
            value = if (connected) String.format(java.util.Locale.US, "%.1f", tx) else "—",
            unit = if (connected) "Mbps" else "",
            label = "آپلود",
            accent = M.Violet,
        )
    }
}

@Composable
private fun HintSection(connected: Boolean, connecting: Boolean, handshakeAge: Int, engineDetail: String) {
    Text(
        when {
            connected -> "تونل WireGuard فعال · " + faNum(handshakeAge) + " ثانیه از آخرین دست‌دادن"
            connecting -> "دست‌دادن Noise با سرور در جریان است…"
            engineDetail.isNotEmpty() -> engineDetail
            else -> "با یک لمس وصل می‌شوی؛ سرور خودکار انتخاب می‌شود"
        },
        fontSize = 11.sp,
        color = M.Muted2,
        fontFamily = MolidFont,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    summary: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(open) {
        if (open) {
            delay(340)
            bring.bringIntoView()
        }
    }
    GlassCard(
        radius = 15.dp,
        alpha = if (open) 0.075f else 0.045f,
        modifier = Modifier.bringIntoViewRequester(bring),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(31.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(tint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, Modifier.size(17.dp), tint = tint)
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 10.5.sp,
                        fontFamily = MolidFont,
                        color = M.Muted2,
                    )
                    Text(
                        summary,
                        fontSize = 13.5.sp,
                        fontFamily = MolidFont,
                        fontWeight = FontWeight.SemiBold,
                        color = M.TextDim,
                    )
                }
                Icon(
                    MolidIcons.Chevron,
                    contentDescription = if (open) "بستن" else "باز کردن",
                    Modifier.size(18.dp).rotate(rotation),
                    tint = M.Muted,
                )
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 11.dp)
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.06f))
                    )
                    content()
                }
            }
        }
    }
}

@Composable
private fun StatusBody(
    connected: Boolean,
    connecting: Boolean,
    handshakeAge: Int,
    server: Server,
    ping: Int?,
    timer: String,
    rx: Double,
    tx: Double,
) {
    val rows = listOf(
        "وضعیت" to when {
            connected -> "متصل و رمزنگاری‌شده"
            connecting -> "در حال دست‌دادن"
            else -> "قطع"
        },
        "مدت اتصال" to (if (connected) timer else "—"),
        "پینگ" to (if (ping != null) ltr(faNum(ping) + " ms") else "—"),
        "رمزنگاری" to "ChaCha20-Poly1305",
        "دانلود" to (if (connected) ltr(String.format(java.util.Locale.US, "%.1f", rx) + " Mbps") else "—"),
        "آپلود" to (if (connected) ltr(String.format(java.util.Locale.US, "%.1f", tx) + " Mbps") else "—"),
        "آخرین دست‌دادن" to (if (handshakeAge > 0) faNum(handshakeAge) + " ثانیه پیش" else "—"),
        "مسیر خروج" to server.title,
    )
    Column(Modifier.padding(horizontal = 13.dp, vertical = 4.dp)) {
        rows.forEachIndexed { index, (k, v) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(k, fontSize = 10.5.sp, fontFamily = MolidFont, color = M.Muted2)
                Spacer(Modifier.weight(1f))
                Text(
                    v,
                    fontSize = 12.sp,
                    fontFamily = MolidFont,
                    fontWeight = FontWeight.SemiBold,
                    color = if (v == "قطع" || v == "—") M.Muted else M.TextDim,
                    maxLines = 1,
                )
            }
            if (index != rows.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.05f))
                )
            }
        }
    }
}

@Composable
private fun ExitRow(
    server: Server,
    selected: Boolean,
    ping: Int?,
    testing: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) M.Cyan.copy(alpha = 0.07f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagChip(server.flag, 26.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                server.name,
                fontSize = 12.sp,
                fontFamily = MolidFont,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) M.Text else M.TextDim,
            )
            Text(
                server.country,
                fontSize = 9.5.sp,
                fontFamily = MolidFont,
                color = M.Muted2,
            )
        }
        Text(
            if (testing) "···" else if (ping != null) ltr(faNum(ping) + " ms") else "—",
            fontSize = 11.sp,
            fontFamily = MolidFont,
            color = when {
                testing -> M.Muted
                ping == null -> M.Muted2
                ping < 100 -> M.Mint
                ping < 180 -> M.Cyan
                ping < 300 -> M.Amber
                else -> M.Rose
            },
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(15.dp)
                .clip(CircleShape)
                .border(1.dp, if (selected) M.Cyan else Color.White.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(7.dp).background(M.Cyan, CircleShape))
        }
    }
}

@Composable
private fun Metric(
    modifier: Modifier,
    value: String,
    unit: String,
    label: String,
    accent: Color,
) {
    GlassCard(modifier, radius = 14.dp, alpha = 0.055f) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    value,
                    fontSize = 19.sp,
                    fontFamily = MolidFont,
                    fontWeight = FontWeight.Bold,
                    color = M.Text,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        unit,
                        fontSize = 9.sp,
                        fontFamily = MolidFont,
                        color = accent.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 10.5.sp, fontFamily = MolidFont, color = M.Muted2)
        }
        }
    }
}
