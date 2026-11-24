package com.molidvpn.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.molidvpn.ui.icons.MolidIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molidvpn.ui.theme.M
import com.molidvpn.ui.theme.MolidFont

/**
 * The orbit: halo, ticks, rotating gradient arc, comet and a live core.
 * The whole core is the connect button — one tap toggles the tunnel.
 */
@Composable
fun OrbitReactor(
    connected: Boolean,
    timer: String,
    action: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = rememberInfiniteTransition(label = "orbit")
    val spin by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "spin",
    )
    val dash by t.animateFloat(
        0f, -360f,
        infiniteRepeatable(tween(26000, easing = LinearEasing)),
        label = "dash",
    )
    val glow by t.animateFloat(
        0.94f, 1.05f,
        infiniteRepeatable(tween(5500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val coreSize = maxWidth * 0.52f

        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f - 5.dp.toPx()
            val c = Offset(size.width / 2f, size.height / 2f)

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        M.Cyan.copy(alpha = if (connected) 0.26f else 0.07f),
                        M.Violet.copy(alpha = if (connected) 0.15f else 0.05f),
                        Color.Transparent,
                    ),
                    center = c,
                    radius = r * 1.18f * (if (connected) glow else 1f),
                ),
                radius = r * 1.18f * (if (connected) glow else 1f),
                center = c,
            )

            val ticks = 60
            for (i in 0 until ticks) {
                rotate(i * (360f / ticks), c) {
                    drawLine(
                        color = Color.White.copy(alpha = if (connected) 0.20f else 0.09f),
                        start = Offset(c.x, c.y - r + 2.dp.toPx()),
                        end = Offset(c.x, c.y - r + 8.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }

            rotate(dash, c) {
                drawCircle(
                    color = Color.White.copy(alpha = if (connected) 0.16f else 0.08f),
                    radius = r - 15.dp.toPx(),
                    center = c,
                    style = Stroke(
                        1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(3.dp.toPx(), 9.dp.toPx())
                        ),
                    ),
                )
            }

            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = r - 36.dp.toPx(),
                center = c,
                style = Stroke(1.dp.toPx()),
            )

            rotate(spin, c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            M.Cyan.copy(alpha = 0.15f),
                            M.Cyan,
                            M.Mint,
                            M.Violet,
                            Color.Transparent,
                        ),
                        c,
                    ),
                    startAngle = -55f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            if (connected) {
                rotate(spin + 245f, c) {
                    drawCircle(
                        color = Color(0xFFF2FEFF),
                        radius = 3.5.dp.toPx(),
                        center = Offset(c.x, c.y - r + 1.5.dp.toPx()),
                    )
                    drawCircle(
                        color = M.Cyan.copy(alpha = 0.35f),
                        radius = 9.dp.toPx(),
                        center = Offset(c.x, c.y - r + 1.5.dp.toPx()),
                    )
                }
            }
        }

        Core(
            connected = connected,
            timer = timer,
            action = action,
            onToggle = onToggle,
            size = coreSize,
        )
    }
}

@Composable
private fun Core(
    connected: Boolean,
    timer: String,
    action: String,
    onToggle: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.972f else 1f, label = "corePress")

    val top = if (connected) Color(0xFF1B4A55) else Color(0xFF2B2158)
    val bottom = if (connected) Color(0xFF04101A) else Color(0xFF0A0718)
    val ring = if (connected) M.Cyan.copy(alpha = 0.42f) else M.Violet.copy(alpha = 0.5f)

    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(top, bottom)))
            .border(1.dp, ring, CircleShape)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = action,
                onClick = onToggle,
            )
            .semantics {
                contentDescription = action
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (connected) MolidIcons.ShieldCheck else MolidIcons.ShieldAlert,
                contentDescription = null,
                Modifier.size(34.dp),
                tint = if (connected) M.Mint else Color(0xFFBFD2FF),
            )
            if (connected) {
                Spacer(Modifier.height(6.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        "\u202A" + timer + "\u202C",
                        fontSize = 23.sp,
                        fontFamily = MolidFont,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFEEF1FA),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                action,
                fontSize = 12.sp,
                fontFamily = MolidFont,
                fontWeight = FontWeight.Medium,
                color = if (connected) M.Mint.copy(alpha = 0.85f) else M.Muted,
            )
        }
    }
}
