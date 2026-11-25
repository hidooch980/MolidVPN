package com.molidvpn.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import com.molidvpn.core.wg.WgConfig
import com.molidvpn.ui.icons.MolidIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molidvpn.BuildConfig
import com.molidvpn.data.Server
import com.molidvpn.data.SettingsStore
import com.molidvpn.ui.components.GlassCard
import com.molidvpn.ui.components.MolidSwitch
import com.molidvpn.ui.components.RowItem
import com.molidvpn.ui.components.SectionLabel
import com.molidvpn.ui.faNum
import com.molidvpn.ui.theme.M
import com.molidvpn.ui.theme.MolidFont

@Composable
fun SettingsScreen(
    settings: SettingsStore,
    server: Server,
    onCheckUpdate: () -> Unit,
    onToast: (String) -> Unit,
    onEditConfig: () -> Unit,
    onSelfTest: () -> Unit,
    onWarp: () -> Unit,
    onAddLink: () -> Unit,
    profileCount: Int,
    onOpenLink: (String) -> Unit,
) {
    val configSummary = remember(settings.wgConfig) {
        if (settings.wgConfig.isBlank()) "وارد نشده؛ بدون آن اتصال برقرار نمی‌شود"
        else try {
            val c = WgConfig.parse(settings.wgConfig)
            "تنظیم‌شده · " + c.endpoint + " · " + c.fingerprint()
        } catch (e: Exception) {
            "نامعتبر · " + (e.message ?: "")
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "تنظیمات",
            fontSize = 25.sp,
            fontFamily = MolidFont,
            fontWeight = FontWeight.Bold,
            color = M.Text,
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(Modifier.height(8.dp))
        PlanCard(server = server)

        SectionLabel("تونل")
        GlassCard(radius = 16.dp, alpha = 0.055f) {
            Column(Modifier.padding(vertical = 2.dp)) {
                RowItem(
                    icon = MolidIcons.Server,
                    iconTint = M.Mint,
                    title = "پیکربندی تونل",
                    subtitle = configSummary,
                    trailing = {
                        Text(
                            if (settings.wgConfig.isBlank()) "افزودن" else "ویرایش",
                            fontSize = 9.sp,
                            fontFamily = MolidFont,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 12.sp,
                            color = M.Mint,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(M.Mint.copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    },
                    onClick = onEditConfig,
                )
                Hairline()
                RowItem(
                    icon = MolidIcons.Update,
                    iconTint = M.Cyan,
                    title = "اتصال رایگان WARP",
                    subtitle = "بدون لینک و اکانت؛ کلادفلر رایگان",
                    trailing = { Pill("دریافت", M.Cyan) },
                    onClick = onWarp,
                )
                Hairline()
                RowItem(
                    icon = MolidIcons.Search,
                    iconTint = M.Mint,
                    title = "افزودن از لینک",
                    subtitle = "wg:// ، ss:// ، vless:// یا لینک اشتراک",
                    trailing = { Pill("افزودن", M.Mint) },
                    onClick = onAddLink,
                )
                Hairline()
                RowItem(
                    icon = MolidIcons.ShieldCheck,
                    iconTint = M.Cyan,
                    title = "تست موتور تونل",
                    subtitle = "رمزنگاری، دست‌دادن و انتقال داده",
                    trailing = {
                        Icon(
                            MolidIcons.Chevron,
                            contentDescription = null,
                            Modifier.size(14.dp),
                            tint = M.Muted,
                        )
                    },
                    onClick = onSelfTest,
                )
            }
        }

        SectionLabel("هسته‌ها")
        GlassCard(radius = 16.dp, alpha = 0.055f) {
            Column(Modifier.padding(vertical = 2.dp)) {
                RowItem(
                    icon = MolidIcons.Globe,
                    iconTint = M.Mint,
                    title = "WireGuard",
                    subtitle = "هستهٔ داخلی · تونل واقعی روی TUN",
                    trailing = { Pill("داخلی", M.Mint) },
                )
                Hairline()
                RowItem(
                    icon = MolidIcons.Power,
                    iconTint = M.Amber,
                    title = "سایفون",
                    subtitle = "هستهٔ داخلی · بدون نصب اپ دیگر",
                    trailing = { Pill("داخلی", M.Amber) },
                )
                Hairline()
                RowItem(
                    icon = MolidIcons.Shield,
                    iconTint = M.Cyan,
                    title = "Tor",
                    subtitle = "هستهٔ داخلی · بدون Orbot",
                    trailing = { Pill("داخلی", M.Cyan) },
                )
                Hairline()
                RowItem(
                    icon = MolidIcons.Swap,
                    iconTint = M.Violet,
                    title = "Xray",
                    subtitle = if (profileCount > 0) "هستهٔ داخلی · " + faNum(profileCount) + " پروفایل SS/VLESS آماده" else "هستهٔ داخلی · از «افزودن از لینک» پروفایل اضافه کن",
                    trailing = { Pill(if (profileCount > 0) faNum(profileCount) + " آماده" else "خالی", if (profileCount > 0) M.Mint else M.Muted2) },
                )
            }
        }

        SectionLabel("اتصال هوشمند")
        GlassCard(radius = 16.dp, alpha = 0.055f) {
            Column(Modifier.padding(vertical = 2.dp)) {
                RowItem(
                    icon = MolidIcons.ShieldCheck,
                    iconTint = M.Mint,
                    title = "انتخاب هوشمند سرور",
                    subtitle = "سریع‌ترین مسیر خودکار انتخاب می‌شود",
                    trailing = {
                        MolidSwitch(settings.smartSelect) {
                            settings.updateSmartSelect(it)
                            onToast(if (it) "انتخاب هوشمند روشن شد" else "انتخاب سرور آزاد شد")
                        }
                    },
                )
                Hairline()
                RowItem(
                    icon = MolidIcons.Swap,
                    iconTint = M.Cyan,
                    title = "تعویض خودکار در قطعی",
                    subtitle = "اگر مسیر افت کند، بی‌درنگ عوض می‌شود",
                    trailing = {
                        MolidSwitch(settings.autoFailover) {
                            settings.updateFailover(it)
                            onToast(if (it) "تعویض خودکار فعال شد" else "تعویض خودکار خاموش شد")
                        }
                    },
                )
                Hairline()
                RowItem(
                    icon = MolidIcons.Badge,
                    iconTint = M.Violet,
                    title = "تست دوره‌ای پینگ",
                    subtitle = "هر ۱ دقیقه، بدون اینکه متوجه شوی",
                    trailing = {
                        MolidSwitch(settings.autoPing) {
                            settings.updateAutoPing(it)
                            onToast(if (it) "تست دوره‌ای روشن شد" else "تست دوره‌ای متوقف شد")
                        }
                    },
                )
            }
        }

        SectionLabel("حریم خصوصی")
        GlassCard(radius = 16.dp, alpha = 0.055f) {
            Column(Modifier.padding(vertical = 2.dp)) {
                RowItem(
                    icon = MolidIcons.Power,
                    iconTint = M.Rose,
                    title = "قطع اضطراری",
                    subtitle = "اگر تونل قطع شود، اینترنت بسته می‌ماند",
                    trailing = {
                        MolidSwitch(settings.killSwitch) {
                            settings.updateKillSwitch(it)
                            onToast(if (it) "قطع اضطراری فعال شد" else "قطع اضطراری خاموش شد")
                        }
                    },
                )
                Hairline()
                RowItem(
                    icon = MolidIcons.Server,
                    iconTint = M.Amber,
                    title = "DNS امن",
                    subtitle = "پرس‌وجوها رمزنگاری‌شده ارسال می‌شوند",
                    trailing = {
                        MolidSwitch(settings.secureDns) {
                            settings.updateSecureDns(it)
                            onToast(if (it) "DNS امن روشن شد" else "DNS امن خاموش شد")
                        }
                    },
                )
            }
        }

        SectionLabel("بروزرسانی")
        GlassCard(radius = 16.dp, alpha = 0.055f) {
            RowItem(
                icon = MolidIcons.Update,
                iconTint = M.Mint,
                title = "بروزرسانی هوشمند",
                subtitle = "نسخهٔ " + faNum(BuildConfig.VERSION_NAME) + " · ساخت " + faNum(BuildConfig.VERSION_CODE) + " · امضا تأیید شد",
                trailing = {
                    MolidSwitch(settings.smartUpdate) {
                        settings.updateSmartUpdate(it)
                        onToast(if (it) "بروزرسانی خودکار روشن شد" else "بروزرسانی خودکار خاموش شد")
                    }
                },
                onClick = onCheckUpdate,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "MolidoVPN · نسخهٔ " + faNum(BuildConfig.VERSION_NAME) + " (" + faNum(BuildConfig.VERSION_CODE) + ")",
            fontSize = 8.5.sp,
            fontFamily = MolidFont,
            color = M.Muted2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PlanCard(server: Server) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        M.Violet.copy(alpha = 0.22f),
                        M.Cyan.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0.02f),
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("م", fontSize = 16.sp, fontFamily = MolidFont, fontWeight = FontWeight.Bold, color = M.Text)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "حساب آزاد",
                    fontSize = 12.5.sp,
                    fontFamily = MolidFont,
                    fontWeight = FontWeight.Bold,
                    color = M.Text,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "۱۰ GB ماهانه · ۰ از ۳ دستگاه",
                    fontSize = 8.5.sp,
                    fontFamily = MolidFont,
                    color = M.TextDim.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp).background(M.Mint, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "مسیر فعال: " + server.title,
                        fontSize = 8.sp,
                        fontFamily = MolidFont,
                        color = M.Mint.copy(alpha = 0.85f),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "ارتقا",
                fontSize = 9.5.sp,
                fontFamily = MolidFont,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF160F2E),
                lineHeight = 12.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(M.Mint)
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.05f))
    )
}
@Composable
private fun Pill(text: String, tint: Color) {
    Text(
        text,
        fontSize = 9.sp,
        fontFamily = MolidFont,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 12.sp,
        color = tint,
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}