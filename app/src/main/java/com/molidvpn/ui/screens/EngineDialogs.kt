package com.molidvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molidvpn.core.wg.WgConfig
import com.molidvpn.core.wg.WgSelfTest
import com.molidvpn.ui.faNum
import com.molidvpn.ui.theme.M
import com.molidvpn.ui.theme.MolidFont

@Composable
private fun DialogShell(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    body: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 18.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF20B0E1B))
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(22.dp))
                .padding(16.dp),
        ) {
            Text(title, fontSize = 15.sp, fontFamily = MolidFont, fontWeight = FontWeight.Bold, color = M.Text)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 9.sp, fontFamily = MolidFont, color = M.Muted, lineHeight = 15.sp)
            Spacer(Modifier.height(12.dp))
            body()
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(CircleShape)
            .background(if (primary) M.Mint else Color.White.copy(alpha = 0.07f))
            .border(1.dp, if (primary) M.Mint else Color.White.copy(alpha = 0.10f), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontFamily = MolidFont,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 13.sp,
            color = if (primary) Color(0xFF0A1420) else M.TextDim,
        )
    }
}

/** Paste a wg.conf: keys, address, DNS, routes and endpoint are all derived from it. */
@Composable
fun WgConfigDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

    DialogShell(
        title = "پیکربندی تونل",
        subtitle = "متن فایل WireGuard را بچسبان. PrivateKey، PublicKey، Address، DNS، AllowedIPs و Endpoint خودکار خوانده می‌شوند.",
        onDismiss = onDismiss,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 190.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF06080F))
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
                .padding(10.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    "[Interface]\nPrivateKey = …\nAddress = 10.0.0.2/32\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = …\nEndpoint = host:51820\nAllowedIPs = 0.0.0.0/0",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = M.Muted2.copy(alpha = 0.6f),
                    lineHeight = 14.sp,
                )
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    textStyle = TextStyle(
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = M.TextDim,
                        lineHeight = 14.sp,
                    ),
                    cursorBrush = SolidColor(M.Mint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 170.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error!!, fontSize = 8.5.sp, fontFamily = MolidFont, color = M.Rose)
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("ذخیره", true, {
                if (text.isBlank()) {
                    error = "متن پیکربندی خالی است"
                    return@ActionButton
                }
                try {
                    val cfg = WgConfig.parse(text)
                    onSave(text.trim())
                    onDismiss()
                } catch (e: Exception) {
                    error = e.message ?: "پیکربندی نامعتبر است"
                }
            })
            ActionButton("انصراف", false, onDismiss)
        }
    }
}

@Composable
fun SelfTestDialog(
    running: Boolean,
    results: List<WgSelfTest.Check>?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogShell(
        title = "تست موتور تونل",
        subtitle = "بردارهای مرجع RFC و یک دست‌دادن کامل بین دو همتای محلی؛ بدون نیاز به سرور.",
        onDismiss = onDismiss,
    ) {
        if (running || results == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = M.Mint,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("در حال اجرا…", fontSize = 10.sp, fontFamily = MolidFont, color = M.TextDim)
            }
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                results.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(if (c.ok) M.Mint.copy(alpha = 0.16f) else M.Rose.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (c.ok) "✓" else "✗",
                                fontSize = 8.sp,
                                color = if (c.ok) M.Mint else M.Rose,
                            )
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(
                            c.name,
                            fontSize = 9.5.sp,
                            fontFamily = MolidFont,
                            color = M.TextDim,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            c.detail,
                            fontSize = 7.5.sp,
                            fontFamily = MolidFont,
                            color = M.Muted2,
                        )
                    }
                }
                val failed = results.count { !it.ok }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (failed == 0) "همهٔ " + faNum(results.size) + " بررسی موفق بود"
                    else faNum(failed) + " مورد ناموفق",
                    fontSize = 9.sp,
                    fontFamily = MolidFont,
                    fontWeight = FontWeight.SemiBold,
                    color = if (failed == 0) M.Mint else M.Rose,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("تکرار تست", true, onRetry)
            ActionButton("بستن", false, onDismiss)
        }
    }
}
