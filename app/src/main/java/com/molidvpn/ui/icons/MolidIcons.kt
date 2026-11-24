package com.molidvpn.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn line icons so the app does not carry the whole Material icon pack.
 * Every icon is a 24x24 vector tinted by [Icon].
 */
private fun line(name: String, strokeWidth: Float = 1.7f, draw: PathBuilder.() -> Unit): ImageVector {
    val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    builder.path(
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = draw,
    )
    return builder.build()
}

private fun solid(name: String, draw: PathBuilder.() -> Unit): ImageVector {
    val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    builder.path(
        fill = SolidColor(Color.White),
        stroke = null,
        pathBuilder = draw,
    )
    return builder.build()
}

private fun PathBuilder.shield() {
    moveTo(12f, 3f)
    lineTo(4.8f, 5.9f)
    lineTo(4.8f, 11.6f)
    curveTo(4.8f, 16.1f, 7.9f, 20.1f, 12f, 21.2f)
    curveTo(16.1f, 20.1f, 19.2f, 16.1f, 19.2f, 11.6f)
    lineTo(19.2f, 5.9f)
    close()
}

object MolidIcons {
    val Shield: ImageVector = line("Shield") { shield() }

    val ShieldCheck: ImageVector = line("ShieldCheck") {
        shield()
        moveTo(8.9f, 12.1f)
        lineTo(11.3f, 14.5f)
        lineTo(15.5f, 10.1f)
    }

    val ShieldAlert: ImageVector = line("ShieldAlert") {
        shield()
        moveTo(12f, 8.4f)
        lineTo(12f, 13f)
        moveTo(12f, 16.2f)
        lineTo(12.01f, 16.2f)
    }

    val Globe: ImageVector = line("Globe", 1.6f) {
        moveTo(12f, 3.2f)
        curveTo(16.9f, 3.2f, 20.8f, 7.1f, 20.8f, 12f)
        curveTo(20.8f, 16.9f, 16.9f, 20.8f, 12f, 20.8f)
        curveTo(7.1f, 20.8f, 3.2f, 16.9f, 3.2f, 12f)
        curveTo(3.2f, 7.1f, 7.1f, 3.2f, 12f, 3.2f)
        close()
        moveTo(3.4f, 12f)
        lineTo(20.6f, 12f)
        moveTo(12f, 3.2f)
        curveTo(9.4f, 5.9f, 8.2f, 8.9f, 8.2f, 12f)
        curveTo(8.2f, 15.1f, 9.4f, 18.1f, 12f, 20.8f)
        curveTo(14.6f, 18.1f, 15.8f, 15.1f, 15.8f, 12f)
        curveTo(15.8f, 8.9f, 14.6f, 5.9f, 12f, 3.2f)
        close()
    }

    val Sparkle: ImageVector = solid("Sparkle") {
        moveTo(12f, 2.6f)
        curveTo(12.8f, 9.6f, 14.4f, 11.2f, 21.4f, 12f)
        curveTo(14.4f, 12.8f, 12.8f, 14.4f, 12f, 21.4f)
        curveTo(11.2f, 14.4f, 9.6f, 12.8f, 2.6f, 12f)
        curveTo(9.6f, 11.2f, 11.2f, 9.6f, 12f, 2.6f)
        close()
    }

    val Sliders: ImageVector = line("Sliders") {
        moveTo(4f, 7.2f)
        lineTo(20f, 7.2f)
        moveTo(4f, 12f)
        lineTo(20f, 12f)
        moveTo(4f, 16.8f)
        lineTo(20f, 16.8f)
        moveTo(9f, 5.2f)
        lineTo(9f, 9.2f)
        moveTo(15f, 10f)
        lineTo(15f, 14f)
        moveTo(7f, 14.8f)
        lineTo(7f, 18.8f)
    }

    val Server: ImageVector = line("Server", 1.5f) {
        moveTo(3.8f, 5.6f)
        lineTo(20.2f, 5.6f)
        lineTo(20.2f, 10.4f)
        lineTo(3.8f, 10.4f)
        close()
        moveTo(3.8f, 13.6f)
        lineTo(20.2f, 13.6f)
        lineTo(20.2f, 18.4f)
        lineTo(3.8f, 18.4f)
        close()
        moveTo(7f, 8f)
        lineTo(7.01f, 8f)
        moveTo(7f, 16f)
        lineTo(7.01f, 16f)
    }

    val Swap: ImageVector = line("Swap") {
        moveTo(4.5f, 9f)
        lineTo(19.5f, 9f)
        moveTo(16.2f, 5.7f)
        lineTo(19.5f, 9f)
        lineTo(16.2f, 12.3f)
        moveTo(19.5f, 15f)
        lineTo(4.5f, 15f)
        moveTo(7.8f, 11.7f)
        lineTo(4.5f, 15f)
        lineTo(7.8f, 18.3f)
    }

    val Update: ImageVector = line("Update") {
        moveTo(12f, 3.8f)
        lineTo(12f, 14.6f)
        moveTo(8.2f, 10.8f)
        lineTo(12f, 14.6f)
        lineTo(15.8f, 10.8f)
        moveTo(4.8f, 17.4f)
        lineTo(4.8f, 19.8f)
        lineTo(19.2f, 19.8f)
        lineTo(19.2f, 17.4f)
    }

    val Power: ImageVector = line("Power") {
        moveTo(12f, 3.4f)
        lineTo(12f, 11.4f)
        moveTo(7.4f, 6.2f)
        curveTo(5.6f, 7.5f, 4.5f, 9.6f, 4.5f, 12f)
        curveTo(4.5f, 16.1f, 7.9f, 19.5f, 12f, 19.5f)
        curveTo(16.1f, 19.5f, 19.5f, 16.1f, 19.5f, 12f)
        curveTo(19.5f, 9.6f, 18.4f, 7.5f, 16.6f, 6.2f)
    }

    val Chevron: ImageVector = line("Chevron", 2f) {
        moveTo(6.5f, 9.5f)
        lineTo(12f, 15f)
        lineTo(17.5f, 9.5f)
    }

    val Badge: ImageVector = line("Badge", 1.6f) {
        moveTo(12f, 3.2f)
        curveTo(16.9f, 3.2f, 20.8f, 7.1f, 20.8f, 12f)
        curveTo(20.8f, 16.9f, 16.9f, 20.8f, 12f, 20.8f)
        curveTo(7.1f, 20.8f, 3.2f, 16.9f, 3.2f, 12f)
        curveTo(3.2f, 7.1f, 7.1f, 3.2f, 12f, 3.2f)
        close()
        moveTo(8.4f, 12.2f)
        lineTo(10.9f, 14.7f)
        lineTo(15.8f, 9.6f)
    }

    val Search: ImageVector = line("Search") {
        moveTo(10.8f, 4.2f)
        curveTo(14.4f, 4.2f, 17.4f, 7.1f, 17.4f, 10.8f)
        curveTo(17.4f, 14.4f, 14.4f, 17.4f, 10.8f, 17.4f)
        curveTo(7.1f, 17.4f, 4.2f, 14.4f, 4.2f, 10.8f)
        curveTo(4.2f, 7.1f, 7.1f, 4.2f, 10.8f, 4.2f)
        close()
        moveTo(15.6f, 15.6f)
        lineTo(20f, 20f)
    }

    val Refresh: ImageVector = line("Refresh") {
        moveTo(19.2f, 12f)
        curveTo(19.2f, 16f, 16f, 19.2f, 12f, 19.2f)
        curveTo(8f, 19.2f, 4.8f, 16f, 4.8f, 12f)
        curveTo(4.8f, 8f, 8f, 4.8f, 12f, 4.8f)
        curveTo(14.4f, 4.8f, 16.6f, 6f, 18f, 7.8f)
        moveTo(18.6f, 3.8f)
        lineTo(18.6f, 8.4f)
        lineTo(14f, 8.4f)
    }

    val Help: ImageVector = line("Help", 1.6f) {
        moveTo(12f, 3.2f)
        curveTo(16.9f, 3.2f, 20.8f, 7.1f, 20.8f, 12f)
        curveTo(20.8f, 16.9f, 16.9f, 20.8f, 12f, 20.8f)
        curveTo(7.1f, 20.8f, 3.2f, 16.9f, 3.2f, 12f)
        curveTo(3.2f, 7.1f, 7.1f, 3.2f, 12f, 3.2f)
        close()
        moveTo(9.6f, 9.4f)
        curveTo(9.8f, 8.2f, 10.8f, 7.4f, 12f, 7.4f)
        curveTo(13.4f, 7.4f, 14.4f, 8.3f, 14.4f, 9.6f)
        curveTo(14.4f, 11.4f, 12f, 11.2f, 12f, 13.4f)
        moveTo(12f, 16.4f)
        lineTo(12.01f, 16.4f)
    }
}
