package com.molidvpn.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molidvpn.ui.theme.M
import com.molidvpn.ui.theme.MolidFont

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    alpha: Float = 0.07f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = alpha), Color.White.copy(alpha = alpha * 0.35f))
                ), RoundedCornerShape(radius)
            )
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(radius))
    ) { content() }
}

@Composable
fun Pill(
    text: String,
    dot: Color? = null,
    icon: ImageVector? = null,
    tint: Color = M.TextDim,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (selected) M.Violet.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) M.Violet.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f), CircleShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(5.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        if (icon != null) {
            Icon(icon, null, Modifier.size(12.dp), tint = tint)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, fontSize = 10.sp, color = tint, fontFamily = MolidFont, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun FlagChip(flag: String, size: Dp = 34.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(flag, fontSize = (size.value * 0.52f).sp, fontFamily = MolidFont)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 10.5.sp,
        color = M.Muted,
        fontFamily = MolidFont,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp),
    )
}

/** RTL-aware switch: on = thumb at the end (left in RTL). */
@Composable
fun MolidSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateDpAsState(if (checked) 34.dp else 34.dp, label = "track")
    val knob = 16.dp
    val travel by animateDpAsState(if (checked) 0.dp else 14.dp, tween(180), label = "knob")
    Box(
        Modifier
            .size(width = 36.dp, height = 22.dp)
            .clip(CircleShape)
            .background(if (checked) M.Mint else Color.White.copy(alpha = 0.13f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 3.dp)) {
            Box(
                Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(knob)
                    .background(if (checked) Color(0xFF05130D) else Color(0xFFE7EAF5), CircleShape)
            )
        }
    }
}

@Composable
fun MiniBars(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val heights = listOf(5, 8, 11, 14, 17)
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
        heights.forEachIndexed { i, h ->
            val on = active && i <= (level * 4).toInt()
            Box(
                Modifier
                    .width(2.dp)
                    .height(h.dp)
                    .background(
                        if (on) M.Cyan.copy(alpha = if (i >= 3) 0.95f else 0.7f)
                        else Color.White.copy(alpha = 0.12f),
                        RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

@Composable
fun RowItem(
    icon: ImageVector? = null,
    iconTint: Color = M.Muted,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(15.dp), tint = iconTint) }
            Spacer(Modifier.width(9.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 11.5.sp, color = M.Text, fontFamily = MolidFont, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(1.dp))
            Text(subtitle, fontSize = 8.5.sp, color = M.Muted2, fontFamily = MolidFont, lineHeight = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
fun ToastCard(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xF20A0E18))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(13.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 10.sp, color = M.TextDim, fontFamily = MolidFont, textAlign = TextAlign.Center)
    }
}
