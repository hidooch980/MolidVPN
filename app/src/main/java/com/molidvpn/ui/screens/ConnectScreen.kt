package com.molidvpn.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import com.molidvpn.ui.faNum
import com.molidvpn.ui.icons.MolidIcons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molidvpn.core.free.FreePool
import com.molidvpn.core.wg.TunnelProfiles
import com.molidvpn.data.Server
import com.molidvpn.data.SettingsStore
import kotlinx.coroutines.launch
import com.molidvpn.net.Smart
import com.molidvpn.ui.components.FlagChip
import androidx.compose.ui.graphics.vector.ImageVector
import com.molidvpn.ui.components.GlassCard
import com.molidvpn.ui.components.OrbPhase
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
import com.molidvpn.update.UpdateState
import com.molidvpn.update.Updater
import kotlinx.coroutines.delay

@Composable
fun ConnectScreen(
    connected: Boolean,
    connecting: Boolean,
    failed: Boolean,
    engineDetail: String,
    engine: String,
    wgProfile: String,
    handshakeAge: Int,
    startedAt: Long,
    server: Server,
    ping: Int?,
    rx: Double,
    tx: Double,
    servers: List<Server>,
    mode: String,
    country: String,
    onCountry: (String) -> Unit,
    myIp: String?,
    autoExit: Boolean,
    onMode: (String) -> Unit,
    onProfile: (String) -> Unit,
    onAutoExit: () -> Unit,
    onOpenLink: (String) -> Unit,
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
    // One value drives the orbit, the button and the headline, so a
    // connection that is still handshaking can never look finished.
    val phase = when {
        connected -> OrbPhase.CONNECTED
        connecting -> OrbPhase.CONNECTING
        failed -> OrbPhase.FAILED
        else -> OrbPhase.IDLE
    }
    val secs = if (connected && startedAt > 0) ((now - startedAt) / 1000).coerceAtLeast(0) else 0
    val timer = fmtTimer(secs)
    val clock = fmtClock(secs)
    var sheet by remember { mutableIntStateOf(0) }
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
        val updateVersion = UpdateState.version
        if (updateVersion != null) {
            Spacer(Modifier.height(6.dp))
            UpdateBanner(
                version = updateVersion,
                progress = UpdateState.progress,
                error = UpdateState.error,
                onUpdate = {
                    UpdateState.retry()
                    Updater.install(context, onToast)
                },
            )
            Spacer(Modifier.height(10.dp))
        }
        sections.forEach { section ->
            // Mode and exit now share one compact row, so this section is merged away.
            if (section.id == "exits_card" || section.id == "modes") return@forEach
            when (section.id) {
                "orb" -> OrbSection(phase, clock, onToggle, section.scale)
                "state" -> StateSection(connected, connecting, myIp)
                "status_card" -> LocationCard(
                    country = country,
                    onClick = { sheet = 1 },
                )
                "metrics" -> MetricsSection(connected, ping, rx, tx)
                "hint" -> HintSection(connected, connecting, failed, handshakeAge, engineDetail, engine)
            }
            Spacer(Modifier.height(section.gap.dp))
        }
    }

    if (sheet == 1) {
        LocationSheet(
            country = country,
            onPick = { code ->
                sheet = 0
                onCountry(code)
                onToast(
                    if (code == "auto") "لوکیشن: خودکار — سریع‌ترین سرور"
                    else "لوکیشن: " + FreePool.flagOf(code) + " " + FreePool.nameOf(code)
                )
            },
            onDismiss = { sheet = 0 },
        )
    }
}

private val MODES = listOf(
    Triple("smart", "هوشمند", MolidIcons.Sparkle),
    Triple("safe", "امن", MolidIcons.ShieldCheck),
    Triple("free", "رایگان WARP", MolidIcons.Update),
)

@Composable
private fun ModesSection(mode: String, onMode: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MODES.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.width(6.dp))
            val active = mode == item.first
            Pill(
                text = item.second,
                icon = item.third,
                dot = if (active) M.Mint else null,
                tint = if (active) M.Text else M.Muted,
                selected = active,
                onClick = { onMode(item.first) },
            )
        }
    }
}

@Composable
private fun OrbSection(
    phase: OrbPhase,
    clock: String,
    onToggle: () -> Unit,
    scale: Float,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        OrbitReactor(
            phase = phase,
            timer = clock,
            onToggle = onToggle,
            modifier = Modifier.fillMaxWidth(scale * 0.80f).aspectRatio(1f),
        )
    }
}

@Composable
private fun UpdateBanner(version: String, progress: Int, error: String?, onUpdate: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(M.Mint.copy(alpha = 0.10f))
            .border(1.dp, M.Mint.copy(alpha = 0.32f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "نسخهٔ " + faNum(version) + " آماده است",
                fontSize = 11.5.sp,
                fontFamily = MolidFont,
                fontWeight = FontWeight.Bold,
                color = M.Text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    progress >= 0 -> "در حال دانلود بسته…"
                    error != null -> error
                    else -> "قبل از نصب، امضا و SHA-256 بررسی می‌شود"
                },
                fontSize = 8.5.sp,
                fontFamily = MolidFont,
                color = if (error != null && progress < 0) M.Rose else M.TextDim.copy(alpha = 0.85f),
            )
        }
        Text(
            if (progress >= 0) faNum(progress) + "٪" else if (error != null) "تلاش دوباره" else "به‌روزرسانی",
            fontSize = 9.5.sp,
            fontFamily = MolidFont,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF06110C),
            modifier = Modifier
                .clip(CircleShape)
                .background(M.Mint)
                .clickable(enabled = progress < 0) { onUpdate() }
                .padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun StateSection(
    connected: Boolean,
    connecting: Boolean,
    myIp: String?,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (connecting) {
            LinearProgressIndicator(
                modifier = Modifier
                    .width(108.dp)
                    .height(2.dp)
                    .clip(CircleShape),
                color = M.Amber,
                trackColor = Color.White.copy(alpha = 0.08f),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (myIp != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (connected) "IP خروجی" else "IP شما",
                    fontSize = 9.5.sp,
                    color = M.Muted2,
                    fontFamily = MolidFont,
                )
                Spacer(Modifier.width(5.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        myIp,
                        fontSize = 10.sp,
                        color = if (connected) M.Cyan else M.Muted,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** The connection modes offered on the home screen, in the order they are shown. */
@Composable
private fun LocationCard(country: String, onClick: () -> Unit) {
    val node = FreePool.best(country)
    val countries = FreePool.countries()
    val shown = if (country == "auto") (node?.country ?: "ZZ") else country
    val title = if (country == "auto") "خودکار" else FreePool.nameOf(country)
    val sub = when {
        FreePool.busy -> "در حال تست سرورهای عمومی…"
        node == null -> "سرور آماده نیست · هستهٔ سایفون جایگزین می‌شود"
        country == "auto" -> "سریع‌ترین · " + FreePool.nameOf(node.country)
        else -> (countries.firstOrNull { it.code == country }?.count ?: 0).toString() + " سرور در این لوکیشن"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(M.Cyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (shown == "ZZ") Icon(MolidIcons.Globe, contentDescription = null, Modifier.size(16.dp), tint = M.Cyan)
            else Text(FreePool.flagOf(shown), fontSize = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("لوکیشن", fontSize = 8.sp, color = M.Muted2, fontFamily = MolidFont)
            Text(title, fontSize = 13.sp, color = M.Text, fontFamily = MolidFont, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 7.5.sp, color = M.Muted2, fontFamily = MolidFont, lineHeight = 10.sp)
        }
        if (node != null) {
            Text(
                faNum(node.latency) + " ms",
                fontSize = 11.sp,
                color = M.Mint,
                fontFamily = MolidFont,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text("تغییر", fontSize = 9.sp, color = M.Cyan, fontFamily = MolidFont)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationSheet(country: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val countries = FreePool.countries()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = M.Ink2,
        contentColor = M.Text,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .padding(horizontal = 14.dp)
                .padding(bottom = 18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "لوکیشن",
                    fontSize = 13.sp,
                    fontFamily = MolidFont,
                    fontWeight = FontWeight.SemiBold,
                    color = M.Text,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (countries.isEmpty()) "فهرست خالی" else faNum(countries.size) + " کشور",
                    fontSize = 8.5.sp,
                    color = M.Muted2,
                    fontFamily = MolidFont,
                )
            }
            LocationRow(
                flag = "✨",
                title = "خودکار",
                note = "سریع‌ترین سرور به‌صورت خودکار",
                latency = FreePool.best("auto")?.latency,
                selected = country == "auto",
                onClick = { onPick("auto") },
            )
            countries.forEach { c ->
                LocationRow(
                    flag = FreePool.flagOf(c.code),
                    title = FreePool.nameOf(c.code),
                    note = faNum(c.count) + " سرور",
                    latency = c.latency,
                    selected = country == c.code,
                    onClick = { onPick(c.code) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(M.Cyan.copy(alpha = 0.10f))
                    .border(1.dp, M.Cyan.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .clickable(enabled = !FreePool.busy) {
                        scope.launch { FreePool.ensure(ctx, 110_000, force = true) }
                    }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (FreePool.busy) "در حال تست سرورهای عمومی…" else "به‌روزرسانی سرورهای رایگان",
                    fontSize = 10.sp,
                    color = M.Cyan,
                    fontFamily = MolidFont,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (FreePool.status.isNotEmpty()) {
                Text(
                    FreePool.status,
                    fontSize = 8.sp,
                    color = M.Muted2,
                    fontFamily = MolidFont,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
            Text(
                "سرورها از فهرست‌های عمومی گیت‌هاب گرفته و با هستهٔ خود برنامه تست می‌شوند؛ فقط آن‌هایی که واقعاً جواب دادند در فهرست می‌مانند.",
                fontSize = 7.5.sp,
                color = M.Muted2,
                fontFamily = MolidFont,
                lineHeight = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun LocationRow(
    flag: String,
    title: String,
    note: String,
    latency: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) M.Cyan.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) Box(Modifier.size(7.dp).background(M.Cyan, CircleShape)) else Spacer(Modifier.size(7.dp))
        Spacer(Modifier.width(8.dp))
        Text(flag, fontSize = 15.sp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 11.5.sp, color = M.Text, fontFamily = MolidFont, fontWeight = FontWeight.SemiBold)
            Text(note, fontSize = 8.sp, color = M.Muted2, fontFamily = MolidFont)
        }
        Text(
            if (latency == null) "—" else faNum(latency) + " ms",
            fontSize = 10.sp,
            color = if (latency == null) M.Muted2 else M.Mint,
            fontFamily = MolidFont,
        )
    }
}

private data class ConnMode(
    val id: String,
    val title: String,
    val note: String,
    val icon: ImageVector,
    val tint: Color,
)

private val CONN_MODES = listOf(
    ConnMode("smart", "اتوماتیک", "سریع‌ترین مسیر، تعویض خودکار", MolidIcons.Sparkle, M.Cyan),
    ConnMode("free", "وارپ (رایگان)", "کلادفلر · بدون لینک و اکانت", MolidIcons.Update, M.Violet),
    ConnMode("psiphon", "سایفون", "هستهٔ داخلی · بدون نصب اپ", MolidIcons.Power, M.Amber),
    ConnMode("tor", "تور", "هستهٔ داخلی · بدون Orbot", MolidIcons.Shield, M.Mint),
    ConnMode("xray", "Xray (SS/VLESS)", "هستهٔ داخلی · لینک لازم است", MolidIcons.Swap, M.Violet),
)

@Composable
private fun PickerRow(
    mode: String,
    server: Server,
    autoExit: Boolean,
    onMode: () -> Unit,
    onExit: () -> Unit,
) {
    val current = CONN_MODES.firstOrNull { it.id == mode } ?: CONN_MODES.first()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        MiniPicker(
            modifier = Modifier.weight(1f),
            title = "حالت اتصال",
            value = current.title,
            tint = current.tint,
            icon = MolidIcons.Swap,
            onClick = onMode,
        )
        MiniPicker(
            modifier = Modifier.weight(1f),
            title = "مکان خروجی",
            value = (if (autoExit) "خودکار · " else "") + server.name,
            tint = M.Cyan,
            icon = MolidIcons.Globe,
            onClick = onExit,
        )
    }
}

/** One-line summary chip: label above, current choice below, both inside a single row. */
@Composable
private fun MiniPicker(
    modifier: Modifier,
    title: String,
    value: String,
    tint: Color,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(13.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = tint) }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 7.5.sp, fontFamily = MolidFont, color = M.Muted2)
            Text(
                value,
                fontSize = 10.5.sp,
                fontFamily = MolidFont,
                fontWeight = FontWeight.SemiBold,
                color = M.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSheet(
    current: String,
    profile: String,
    onProfile: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = M.Ink2,
        contentColor = M.Text,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .padding(horizontal = 14.dp)
                .padding(bottom = 18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "حالت اتصال",
                fontSize = 13.sp,
                fontFamily = MolidFont,
                fontWeight = FontWeight.SemiBold,
                color = M.Text,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            )
            CONN_MODES.forEach { item ->
                ModeRow(item, item.id == current) { onPick(item.id) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "پروفایل تونل · WireGuard",
                fontSize = 13.sp,
                fontFamily = MolidFont,
                fontWeight = FontWeight.SemiBold,
                color = M.Text,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            )
            TunnelProfiles.all.forEach { item ->
                ProfileRow(item, item.id == profile) { onProfile(item.id) }
            }
        }
    }
}

@Composable
private fun ProfileRow(item: TunnelProfiles.Profile, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) M.Cyan.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(Modifier.size(7.dp).background(M.Cyan, CircleShape))
        } else {
            Spacer(Modifier.size(7.dp))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(M.Cyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(MolidIcons.Sliders, contentDescription = null, modifier = Modifier.size(14.dp), tint = M.Cyan) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 11.sp, color = M.Text, fontFamily = MolidFont, fontWeight = FontWeight.SemiBold)
            Text(item.note, fontSize = 8.sp, color = M.Muted2, fontFamily = MolidFont, lineHeight = 11.sp)
        }
    }
}

@Composable
private fun ModeRow(item: ConnMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) item.tint.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(Modifier.size(7.dp).background(item.tint, CircleShape))
        } else {
            Spacer(Modifier.size(7.dp))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(item.tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(item.icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = item.tint) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 11.sp, color = M.Text, fontFamily = MolidFont, fontWeight = FontWeight.SemiBold)
            Text(item.note, fontSize = 8.sp, color = M.Muted2, fontFamily = MolidFont, lineHeight = 11.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExitSheet(
    server: Server,
    servers: List<Server>,
    auto: Boolean,
    onAuto: () -> Unit,
    onSelect: (Server) -> Unit,
    onDismiss: () -> Unit,
) {
    val bestPing = servers.mapNotNull { Smart.pings[it.id] }.minOrNull()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = M.Ink2,
        contentColor = M.Text,
        dragHandle = null,
    ) {
        Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 18.dp)) {
            Text(
                "مکان خروجی",
                fontSize = 13.sp,
                fontFamily = MolidFont,
                fontWeight = FontWeight.SemiBold,
                color = M.Text,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            )
            AutoExitRow(
                selected = auto,
                bestPing = bestPing,
                testing = Smart.testing.isNotEmpty() && bestPing == null,
                onClick = onAuto,
            )
            servers.forEach { s ->
                ExitRow(
                    server = s,
                    selected = !auto && s.id == server.id,
                    ping = Smart.pings[s.id],
                    testing = Smart.testing.contains(s.id),
                    onClick = { onSelect(s) },
                )
            }
            Text(
                if (auto) "فهرست هر ۱ دقیقه تست می‌شود و سریع‌ترین مسیر خودکار انتخاب می‌شود"
                else "مسیر دستی ثابت می‌ماند؛ «خودکار» را بزن تا انتخاب دوباره به برنامه برگردد",
                fontSize = 7.5.sp,
                fontFamily = MolidFont,
                color = M.Muted2,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            )
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

/** The automatic exit: fastest path, re-chosen every minute. */
@Composable
private fun AutoExitRow(
    selected: Boolean,
    bestPing: Int?,
    testing: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) M.Violet.copy(alpha = 0.09f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(M.Violet.copy(alpha = if (selected) 0.2f else 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MolidIcons.Sparkle,
                contentDescription = null,
                tint = if (selected) M.Violet else M.Muted,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "خودکار",
                fontSize = 12.sp,
                fontFamily = MolidFont,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) M.Text else M.TextDim,
            )
            Text(
                "سریع‌ترین مسیر · تعویض خودکار",
                fontSize = 9.5.sp,
                fontFamily = MolidFont,
                color = M.Muted2,
            )
        }
        Text(
            when {
                testing -> "···"
                bestPing != null -> ltr(faNum(bestPing) + " ms")
                else -> "—"
            },
            fontSize = 11.sp,
            fontFamily = MolidFont,
            color = when {
                testing -> M.Muted
                bestPing == null -> M.Muted2
                bestPing < 100 -> M.Mint
                bestPing < 180 -> M.Cyan
                bestPing < 300 -> M.Amber
                else -> M.Rose
            },
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(15.dp)
                .clip(CircleShape)
                .border(1.dp, if (selected) M.Violet else Color.White.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(7.dp).background(M.Violet, CircleShape))
        }
    }
}

@Composable
private fun MetricsSection(connected: Boolean, ping: Int?, rx: Double, tx: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Metric(
            Modifier.weight(1f),
            value = if (ping != null) faNum(ping) else "—",
            unit = if (ping != null) "ms" else "",
            label = "پینگ گوگل",
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
private fun HintSection(
    connected: Boolean,
    connecting: Boolean,
    failed: Boolean,
    handshakeAge: Int,
    engineDetail: String,
    engine: String,
) {
    // The hint describes the engine that is really carrying traffic, not the
    // mode the user picked: Smart mode can fall back from WireGuard to Psiphon.
    val name = when (engine) {
        "psiphon" -> "سایفون"
        "tor" -> "تور"
        "xray" -> "Xray"
        else -> "WireGuard"
    }
    Text(
        when {
            connected && engine == "wireguard" ->
                "تونل WireGuard فعال · " + faNum(handshakeAge) + " ثانیه از آخرین دست‌دادن"
            connected && engine.isNotEmpty() -> "تونل $name فعال است · ترافیک از سرورهای $name می‌گذرد"
            connecting && engineDetail.isNotEmpty() -> engineDetail
            connecting && engine.isNotEmpty() -> "اتصال به $name در جریان است…"
            connecting -> "دست‌دادن Noise با سرور در جریان است…"
            failed -> engineDetail.ifEmpty { "اتصال برقرار نشد؛ دوباره تلاش کن" }
            engineDetail.isNotEmpty() -> engineDetail
            else -> "با یک لمس وصل می‌شوی؛ سرور خودکار انتخاب می‌شود"
        },
        fontSize = 11.sp,
        color = if (failed) M.Rose.copy(alpha = 0.85f) else M.Muted2,
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