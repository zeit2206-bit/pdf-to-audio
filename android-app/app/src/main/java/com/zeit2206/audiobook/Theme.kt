package com.zeit2206.audiobook

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// Palette from the Figma design ("Audiobook App — Android").
object Palette {
    val bg = Color(0xFF141416)
    val surface = Color(0xFF1E1E22)
    val surface2 = Color(0xFF2A2A30)
    val line = Color(0xFF2E2E34)
    val text = Color(0xFFEDEAE4)
    val muted = Color(0xFF8E8A84)
    val dim = Color(0xFF5E5B57)
    val accent = Color(0xFFF2B544)
    val accentInk = Color(0xFF1A1408)
    val hl = Color(0xFF4A3A12)
    val hlText = Color(0xFFFFF3D6)
}

val Literata = FontFamily(Font(R.font.literata))

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent, onPrimary = Palette.accentInk,
            background = Palette.bg, onBackground = Palette.text,
            surface = Palette.bg, onSurface = Palette.text,
            surfaceVariant = Palette.surface2, onSurfaceVariant = Palette.muted,
        ),
        content = content,
    )
}

/** Icons as SVG path data (same shapes as in the Figma design). */
object Icons {
    private fun filled(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()

    private fun stroked(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    val Play = filled("play", "M7 4.5v15a1 1 0 0 0 1.5 .86l12.5-7.5a1 1 0 0 0 0-1.72L8.5 3.64A1 1 0 0 0 7 4.5z")
    val Pause = filled("pause", "M6 4h4v16H6zM14 4h4v16h-4z")
    val Next = filled("next", "M16 5h2v14h-2zM4 5v14l11-7z")
    val Book = stroked("book", "M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5v14z", "M6.5 17A2.5 2.5 0 0 0 4 19.5 2.5 2.5 0 0 0 6.5 22H20v-5")
    val Video = stroked("video", "M5 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M17 10l4-3v10l-4-3z")
    val Close = stroked("close", "M18 6 6 18M6 6l12 12")
    val Down = stroked("down", "m6 9 6 6 6-6")
    val Up = stroked("up", "m6 15 6-6 6 6")
    val Loop = stroked("loop", "M17 2l4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "M7 22l-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3")
    val Back15 = stroked("back", "M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5")
    val Fwd30 = stroked("fwd", "M21 12a9 9 0 1 1-3-6.7L21 8", "M21 3v5h-5")
    val Add = stroked("add", "M12 5v14M5 12h14")
}
