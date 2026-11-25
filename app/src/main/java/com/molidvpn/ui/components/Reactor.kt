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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molidvpn.ui.icons.MolidIcons
import com.molidvpn.ui.theme.M
import com.molidvpn.ui.theme.MolidFont

/**
 * The four states the connect control can really be in. The ring and the
 * button are drawn from this one value, so "connecting" can never borrow the
 * green look of a finished connection.
 */
enum class OrbPhase { IDLE, CONNECTING, CONNECTED, FAILED }

/**
 * Connect control: one flat button inside a progress ring.
 *
 * The ring states the phase in colour and motion, the button states it in
 * words and carries the single action. Everything decorative that did not
 * carry information (metal rim, tick marks, comet, glint) was removed so the
 * label stays the loudest thing on the screen.
 */
@Composable
fun OrbitReactor(
    phase: OrbPhase,
    timer: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connecting = phase == OrbPhase.CONNECTING
    val accent = when (phase) {
        OrbPhase.CONNECTED -> M.Mint
        OrbPhase.CONNECTING -> M.Amber
        OrbPhase.FAILED -> M.Rose
        OrbPhase.IDLE -> M.Violet
    }
    val accent2 = when (phase) {
        OrbPhase.CONNECTED -> M.Cyan
        OrbPhase.CONNECTING -> Color(0xFFFF9A4D)
        OrbPhase.FAILED -> Color(0xFFFF7D8A)
        OrbPhase.IDLE -> M.Cyan
    }
    val spinMs = when (phase) {
        OrbPhase.CONNECTED -> 7000
        OrbPhase.CONNECTING -> 1400
        else -> 22000
    }

    val t = rememberInfiniteTransition(label = "ring")
    val spin by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(spinMs, easing = LinearEasing)),
        label = "spin",
    )
    val breath by t.animateFloat(
        0.86f, 1.06f,
        infiniteRepeatable(
            tween(if (connecting) 1100 else 3200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "breath",
    )

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // The ring hugs the button: a dial, not a button floating in a big circle.
        val outer = maxWidth
        val coreSize = outer * 0.86f - 46.dp

        Box(Modifier.size(outer), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f * 0.86f
            val c = Offset(size.width / 2f, size.height / 2f)
            val glow = if (phase == OrbPhase.IDLE) 0.5f else breath

            // Soft colour wash behind the ring: the only decoration, and it
            // encodes phase through hue and brightness.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        accent.copy(alpha = 0.20f * glow),
                        accent2.copy(alpha = 0.07f * glow),
                        Color.Transparent,
                    ),
                    center = c,
                    radius = r * 1.14f,
                ),
                radius = r * 1.14f,
                center = c,
            )

            // Track, then the moving arc that shows the phase is alive.
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = r,
                center = c,
                style = Stroke(3.dp.toPx()),
            )
            // The arc is state, not decoration: idle shows only the track,
            // connecting shows a short arc chasing the ring, and a finished
            // tunnel shows the full ring.
            val sweep = when (phase) {
                OrbPhase.CONNECTING -> 140f
                OrbPhase.IDLE -> 0f
                else -> 360f
            }
            if (sweep > 0f) rotate(spin, c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(accent.copy(alpha = 0.35f), accent, accent2, accent.copy(alpha = 0.35f)),
                        c,
                    ),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(4.dp.toPx(), cap = if (sweep < 360f) StrokeCap.Round else StrokeCap.Butt),
                )
            }
        }

        Core(
            phase = phase,
            timer = timer,
            onToggle = onToggle,
            size = coreSize,
        )
        }
    }
}

@Composable
private fun Core(
    phase: OrbPhase,
    timer: String,
    onToggle: () -> Unit,
    size: Dp,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, label = "press")

    val accent = when (phase) {
        OrbPhase.CONNECTED -> M.Mint
        OrbPhase.CONNECTING -> M.Amber
        OrbPhase.FAILED -> M.Rose
        OrbPhase.IDLE -> Color(0xFF9E8CFF)
    }
    val faceTop = when (phase) {
        OrbPhase.CONNECTED -> Color(0xFF0E2B2C)
        OrbPhase.CONNECTING -> Color(0xFF2B2114)
        OrbPhase.FAILED -> Color(0xFF2A161E)
        OrbPhase.IDLE -> Color(0xFF191437)
    }
    val label = when (phase) {
        OrbPhase.CONNECTED -> "قطع اتصال"
        OrbPhase.CONNECTING -> "لغو اتصال"
        OrbPhase.FAILED -> "تلاش دوباره"
        OrbPhase.IDLE -> "اتصال"
    }
    val state = when (phase) {
        OrbPhase.CONNECTED -> "متصل"
        OrbPhase.CONNECTING -> "در حال اتصال"
        OrbPhase.FAILED -> "اتصال ناموفق"
        OrbPhase.IDLE -> "قطع"
    }

    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(faceTop, Color(0xFF07080E))))
            .border(1.5.dp, accent.copy(alpha = 0.45f), CircleShape)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onClick = onToggle,
            )
            .semantics {
                contentDescription = label
                stateDescription = state
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        // One top highlight, so the button reads as a physical key.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(this.size.width * 0.32f, this.size.height * 0.18f),
                    radius = this.size.minDimension * 0.55f,
                ),
                radius = this.size.minDimension / 2f,
                center = center,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                when (phase) {
                    OrbPhase.CONNECTED -> MolidIcons.ShieldCheck
                    OrbPhase.CONNECTING -> MolidIcons.Shield
                    OrbPhase.FAILED -> MolidIcons.ShieldAlert
                    OrbPhase.IDLE -> MolidIcons.Power
                },
                contentDescription = null,
                Modifier.size(30.dp),
                tint = accent,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (phase) {
                    OrbPhase.CONNECTED -> "متصل"
                    OrbPhase.CONNECTING -> "در حال اتصال…"
                    OrbPhase.FAILED -> "اتصال ناموفق"
                    OrbPhase.IDLE -> "قطع"
                },
                fontSize = if (phase == OrbPhase.CONNECTING) 14.sp else 17.sp,
                fontFamily = MolidFont,
                fontWeight = FontWeight.Bold,
                color = M.Text,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            when (phase) {
                OrbPhase.CONNECTED -> {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            "\u202A" + timer + "\u202C",
                            fontSize = 11.sp,
                            fontFamily = MolidFont,
                            color = M.Mint.copy(alpha = 0.85f),
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        label,
                        fontSize = 10.sp,
                        fontFamily = MolidFont,
                        color = M.Mint.copy(alpha = 0.75f),
                    )
                }
                OrbPhase.CONNECTING -> Text(
                    "برای لغو لمس کن",
                    fontSize = 10.sp,
                    fontFamily = MolidFont,
                    color = M.Amber.copy(alpha = 0.85f),
                )
                OrbPhase.FAILED -> Text(
                    label,
                    fontSize = 10.sp,
                    fontFamily = MolidFont,
                    color = M.Rose.copy(alpha = 0.85f),
                )
                OrbPhase.IDLE -> Text(
                    "برای اتصال لمس کن",
                    fontSize = 10.sp,
                    fontFamily = MolidFont,
                    color = M.Muted2,
                )
            }
        }
    }
}
