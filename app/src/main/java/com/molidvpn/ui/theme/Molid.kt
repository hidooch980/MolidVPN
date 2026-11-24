package com.molidvpn.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molidvpn.R

object M {
    val Ink = Color(0xFF06070C)
    val Ink2 = Color(0xFF0A0C14)
    val Violet = Color(0xFF7C5CFF)
    val Cyan = Color(0xFF38D7FF)
    val Mint = Color(0xFF3BF3A4)
    val Rose = Color(0xFFFF6B7A)
    val Amber = Color(0xFFFFC46B)
    val Text = Color(0xFFF4F6FF)
    val TextDim = Color(0xFFD6DCF0)
    val Muted = Color(0xFF8A90A6)
    val Muted2 = Color(0xFF666D84)
    val Line = Color(0x14FFFFFF)
    val Line2 = Color(0x0AFFFFFF)
}

val MolidFont = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

private val MolidTypography = Typography(
    displayLarge = TextStyle(fontFamily = MolidFont, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontFamily = MolidFont, fontSize = 14.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = MolidFont, fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = MolidFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
)

private val Scheme = darkColorScheme(
    primary = M.Violet,
    onPrimary = Color.White,
    secondary = M.Cyan,
    background = M.Ink,
    onBackground = M.Text,
    surface = M.Ink2,
    onSurface = M.Text,
    error = M.Rose,
)

@Composable
fun MolidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = MolidTypography, content = content)
}

/** Frosted glass card used across the app. */
fun Modifier.glass(
    radius: androidx.compose.ui.unit.Dp = 18.dp,
    alpha: Float = 0.06f,
    borderColor: Color = M.Line,
): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = alpha),
                Color.White.copy(alpha = alpha * 0.38f),
            )
        ),
        RoundedCornerShape(radius),
    )
    .border(1.dp, borderColor, RoundedCornerShape(radius))
