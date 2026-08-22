package com.example.burplite.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Glassmorphism design system for BurpLite:
 * dark gradient canvas + soft blurred color blobs + translucent panels.
 */

private val BgBrush = Brush.verticalGradient(
    listOf(Color(0xFF0B1020), Color(0xFF121A38), Color(0xFF1B1440))
)

val GlassColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFB388FF),
    onPrimary = Color(0xFF12082B),
    secondary = Color(0xFF80DEEA),
    onSecondary = Color(0xFF00232A),
    background = Color.Transparent,
    onBackground = Color(0xFFE8EAF6),
    surface = Color(0xFF141A33),
    onSurface = Color(0xFFE8EAF6),
    surfaceVariant = Color(0xFF1E2547),
    onSurfaceVariant = Color(0xFFAFB8D4),
    outline = Color(0xFF565D8A),
    error = Color(0xFFFF6E6E)
)

/** Translucent frosted-panel modifier used across all screens. */
fun Modifier.glassCard(
    shape: Shape = RoundedCornerShape(18.dp),
    alpha: Float = 0.06f
): Modifier = this
    .clip(shape)
    .background(Color.White.copy(alpha = alpha))
    .border(1.dp, Color.White.copy(alpha = 0.14f), shape)

/** Full-screen glassmorphism canvas with decorative blurred blobs. */
@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(BgBrush)) {
        // Decorative light sources behind the glass (blur no-ops below API 31).
        Box(
            Modifier
                .size(300.dp)
                .offset(x = (-90).dp, y = (-70).dp)
                .blur(100.dp)
                .background(Color(0xFF7C4DFF).copy(alpha = 0.30f), CircleShape)
        )
        Box(
            Modifier
                .size(340.dp)
                .offset(x = 230.dp, y = 600.dp)
                .blur(110.dp)
                .background(Color(0xFF00E5FF).copy(alpha = 0.16f), CircleShape)
        )
        Box(
            Modifier
                .size(240.dp)
                .offset(x = 40.dp, y = 330.dp)
                .blur(100.dp)
                .background(Color(0xFFFF4081).copy(alpha = 0.10f), CircleShape)
        )
        content()
    }
}
