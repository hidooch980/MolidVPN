package com.molidvpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.molidvpn.ui.icons.MolidIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.molidvpn.data.Server
import com.molidvpn.net.Smart
import com.molidvpn.ui.components.FlagChip
import com.molidvpn.ui.components.GlassCard
import com.molidvpn.ui.components.MiniBars
import com.molidvpn.ui.faNum
import com.molidvpn.ui.ltr
import com.molidvpn.ui.pingShort
import com.molidvpn.ui.quality
import com.molidvpn.ui.theme.M
import com.molidvpn.ui.theme.MolidFont

@Composable
fun RoutesScreen(
    servers: List<Server>,
    selected: Server,
    onSelect: (Server) -> Unit,
    onTest: (Server) -> Unit,
    onTestAll: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var fastestOnly by remember { mutableStateOf(false) }

    val visible = remember(servers, query, fastestOnly, Smart.pings.toMap()) {
        var list = servers.filter {
            query.isBlank() ||
                it.name.contains(query.trim()) ||
                it.country.contains(query.trim()) ||
                it.ip.contains(query.trim())
        }
        if (fastestOnly) list = list.sortedBy { Smart.pings[it.id] ?: it.seedPing }
        list
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 82.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "مسیرها",
                    fontSize = 25.sp,
                    fontFamily = MolidFont,
                    fontWeight = FontWeight.Bold,
                    color = M.Text,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    faNum(servers.size) + " سرور",
                    fontSize = 9.sp,
                    fontFamily = MolidFont,
                    color = M.Muted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.055f))
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MolidIcons.Search, null, Modifier.size(14.dp), tint = M.Muted2)
                Spacer(Modifier.width(7.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "جستجوی کشور یا IP",
                            fontSize = 10.sp,
                            fontFamily = MolidFont,
                            color = M.Muted2,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = TextStyle(
                            fontSize = 10.sp,
                            fontFamily = MolidFont,
                            color = M.Text,
                        ),
                        cursorBrush = SolidColor(M.Cyan),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip("همه", !fastestOnly) { fastestOnly = false }
                FilterChip("سریع‌ترین", fastestOnly) { fastestOnly = true }
            }
        }

        item { SmartBanner(onTestAll = onTestAll) }

        items(visible, key = { it.id }) { s ->
            RouteRow(
                server = s,
                selected = s.id == selected.id,
                testNow = Smart.testing.contains(s.id),
                ping = Smart.pings[s.id],
                onTest = { onTest(s) },
                onSelect = { onSelect(s) },
            )
        }

        if (visible.isEmpty()) {
            item {
                Text(
                    "سروری با این مشخصات پیدا نشد",
                    fontSize = 10.sp,
                    fontFamily = MolidFont,
                    color = M.Muted2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "فهرست خودکار به‌روزرسانی شد · " + agoText(Smart.lastTestAt),
                    fontSize = 8.5.sp,
                    fontFamily = MolidFont,
                    color = M.Muted2,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    MolidIcons.Refresh,
                    contentDescription = "تست همه",
                    Modifier.size(14.dp).clickable { onTestAll() },
                    tint = M.Muted,
                )
            }
        }
    }
}

private fun agoText(at: Long): String {
    if (at <= 0) return "همین حالا"
    val min = ((System.currentTimeMillis() - at) / 60000).toInt()
    return when {
        min <= 0 -> "همین حالا"
        min == 1 -> "۱ دقیقه پیش"
        else -> faNum(min) + " دقیقه پیش"
    }
}

@Composable
private fun FilterChip(text: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (active) Color.White.copy(alpha = 0.11f) else Color.White.copy(alpha = 0.045f),
        label = "chipBg",
    )
    Text(
        text,
        fontSize = 9.5.sp,
        fontFamily = MolidFont,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) M.Cyan else M.Muted,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 4.dp),
    )
}

@Composable
private fun SmartBanner(onTestAll: () -> Unit) {
    GlassCard(radius = 14.dp, alpha = 0.05f) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(M.Violet.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MolidIcons.Sparkle, null, Modifier.size(14.dp), tint = M.Violet)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "انتخاب هوشمند",
                    fontSize = 10.5.sp,
                    fontFamily = MolidFont,
                    fontWeight = FontWeight.SemiBold,
                    color = M.Text,
                )
                Text(
                    "سریع‌ترین سرور هر ۱ دقیقه تست می‌شود",
                    fontSize = 8.sp,
                    fontFamily = MolidFont,
                    color = M.Muted2,
                )
            }
            Text(
                "خودکار",
                fontSize = 8.5.sp,
                fontFamily = MolidFont,
                color = M.Mint,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(M.Mint.copy(alpha = 0.10f))
                    .clickable { onTestAll() }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun RouteRow(
    server: Server,
    selected: Boolean,
    testNow: Boolean,
    ping: Int?,
    onTest: () -> Unit,
    onSelect: () -> Unit,
) {
    GlassCard(radius = 14.dp, alpha = if (selected) 0.085f else 0.05f) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { onTest() }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlagChip(server.flag, 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        server.name,
                        fontSize = 11.5.sp,
                        fontFamily = MolidFont,
                        fontWeight = FontWeight.SemiBold,
                        color = M.Text,
                    )
                    if (server.badge != null) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            server.badge,
                            fontSize = 7.5.sp,
                            fontFamily = MolidFont,
                            color = M.Cyan,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(M.Cyan.copy(alpha = 0.12f))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.height(1.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        server.country,
                        fontSize = 8.5.sp,
                        fontFamily = MolidFont,
                        color = M.Muted2,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("·", fontSize = 8.sp, color = M.Muted2)
                    Spacer(Modifier.width(4.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            server.ip,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = M.Muted2,
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniBars(level = quality(ping), active = !testNow, modifier = Modifier.height(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (testNow) "···" else ltr(pingShort(ping) + (if (ping != null) " ms" else "")),
                        fontSize = 9.5.sp,
                        fontFamily = MolidFont,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            testNow -> M.Muted
                            ping == null -> M.Muted2
                            ping < 100 -> M.Mint
                            ping < 180 -> M.Cyan
                            ping < 300 -> M.Amber
                            else -> M.Rose
                        },
                    )
                }
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(
                            1.dp,
                            if (selected) M.Cyan else Color.White.copy(alpha = 0.16f),
                            CircleShape,
                        )
                        .clickable { onSelect() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(Modifier.size(8.dp).background(M.Cyan, CircleShape))
                    }
                }
            }
        }
    }
}