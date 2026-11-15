package com.example.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private val CosmicGlassColorScheme = darkColorScheme(
    primary = PearlWhite,
    onPrimary = PearlInk,
    primaryContainer = ElectricVioletContainer,
    onPrimaryContainer = StarlightWhite,
    secondary = ElectricViolet,
    onSecondary = PearlInk,
    secondaryContainer = CyanContainer,
    onSecondaryContainer = SoftCyan,
    tertiary = SoftPinkOrchid,
    onTertiary = PearlInk,
    tertiaryContainer = OrchidContainer,
    onTertiaryContainer = StarlightWhite,
    background = NebulaVoid,
    onBackground = StarlightWhite,
    surface = NebulaNavy,
    onSurface = StarlightWhite,
    surfaceVariant = GlassContainerLow,
    onSurfaceVariant = LavenderMist,
    surfaceContainerLowest = GlassContainerLowest,
    surfaceContainerLow = GlassContainerLow,
    surfaceContainer = GlassContainer,
    surfaceContainerHigh = GlassContainerHigh,
    outline = GlassBorderLight,
    outlineVariant = GlassBorderSubtle,
    error = ErrorCoral,
    onError = Color(0xFF3A0813),
    errorContainer = ErrorCoralContainer,
    onErrorContainer = Color(0xFFFFD9DE)
)

val FolioShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun FolioCurlTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CosmicGlassColorScheme,
        typography = FolioTypography,
        shapes = FolioShapes,
        content = content
    )
}

/**
 * Luminous deep cosmic indigo-violet atmospheric backdrop with ambient radial blooms
 * and subtle orbital contour rings matching the reference glassmorphic aesthetic.
 */
@Composable
fun CosmicGlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        NebulaNavy,
                        NebulaVoid,
                        Color(0xFF0A1329)
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Top-left luminous violet nebula bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NebulaVioletGlow.copy(alpha = 0.55f),
                        NebulaMagentaGlow.copy(alpha = 0.22f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.14f, h * 0.14f),
                    radius = w * 0.92f
                ),
                radius = w * 0.92f,
                center = Offset(w * 0.14f, h * 0.14f)
            )

            // Mid-left soft lavender glow behind primary spotlight card
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NebulaMagentaGlow.copy(alpha = 0.26f),
                        NebulaVioletGlow.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.22f, h * 0.52f),
                    radius = w * 0.72f
                ),
                radius = w * 0.72f,
                center = Offset(w * 0.22f, h * 0.52f)
            )

            // Bottom-right deep cyan-teal ambient glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NebulaCyanGlow.copy(alpha = 0.42f),
                        Color(0xFF15386B).copy(alpha = 0.18f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.88f, h * 0.86f),
                    radius = w * 0.85f
                ),
                radius = w * 0.85f,
                center = Offset(w * 0.88f, h * 0.86f)
            )

            // Subtle concentric orbital contour rings in upper area
            val ringCenter = Offset(w * 0.72f, h * 0.12f)
            for (i in 1..6) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.045f - i * 0.005f),
                    radius = w * (0.16f + i * 0.095f),
                    center = ringCenter,
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }
        }
        content()
    }
}

fun Modifier.glassPanel(
    shape: Shape = RoundedCornerShape(22.dp),
    highlighted: Boolean = false
): Modifier {
    val fillBrush = if (highlighted) {
        Brush.linearGradient(
            colors = listOf(
                VioletCardTop.copy(alpha = 0.92f),
                VioletCardBottom.copy(alpha = 0.88f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                GlassCardTop,
                GlassCardBottom
            )
        )
    }
    val borderBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = if (highlighted) 0.34f else 0.26f),
            Color.White.copy(alpha = 0.07f)
        )
    )
    return this
        .clip(shape)
        .background(brush = fillBrush, shape = shape)
        .border(width = 1.dp, brush = borderBrush, shape = shape)
}
