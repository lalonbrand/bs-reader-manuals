package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightFolioScheme = lightColorScheme(
    primary = BurnishedSienna,
    onPrimary = Color.White,
    primaryContainer = SiennaSoft,
    onPrimaryContainer = DeepTerracotta,
    secondary = BotanicalSage,
    onSecondary = Color.White,
    secondaryContainer = SageContainer,
    onSecondaryContainer = InkCharcoal,
    tertiary = BrassGold,
    onTertiary = Color.White,
    tertiaryContainer = BrassContainer,
    onTertiaryContainer = InkCharcoal,
    background = VellumCream,
    onBackground = InkCharcoal,
    surface = VellumCream,
    onSurface = InkCharcoal,
    surfaceVariant = ParchmentSoft,
    onSurfaceVariant = MutedUmber,
    surfaceContainerLowest = IvoryCard,
    surfaceContainerLow = ParchmentSoft,
    surfaceContainer = Color(0xFFECE3D3),
    surfaceContainerHigh = Color(0xFFE5DAC7),
    outline = WarmBorder,
    outlineVariant = Color(0xFFECE4D6),
    error = ErrorCrimson,
    onError = Color.White
)

private val DarkFolioScheme = darkColorScheme(
    primary = CopperAmber,
    onPrimary = Color(0xFF331105),
    primaryContainer = CopperContainer,
    onPrimaryContainer = WarmAlabaster,
    secondary = SageMist,
    onSecondary = Color(0xFF0E2518),
    secondaryContainer = SageDarkContainer,
    onSecondaryContainer = WarmAlabaster,
    tertiary = GoldCandle,
    onTertiary = Color(0xFF2B1F08),
    tertiaryContainer = GoldDarkContainer,
    onTertiaryContainer = WarmAlabaster,
    background = MahoganyNight,
    onBackground = WarmAlabaster,
    surface = MahoganyNight,
    onSurface = WarmAlabaster,
    surfaceVariant = EspressoSurface,
    onSurfaceVariant = AshTaupe,
    surfaceContainerLowest = Color(0xFF1B1714),
    surfaceContainerLow = EspressoSurface,
    surfaceContainer = EspressoCard,
    surfaceContainerHigh = Color(0xFF342D27),
    outline = DarkStroke,
    outlineVariant = Color(0xFF302923),
    error = ErrorCrimsonDark,
    onError = Color(0xFF3B0909)
)

val FolioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun FolioCurlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkFolioScheme
        else -> LightFolioScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FolioTypography,
        shapes = FolioShapes,
        content = content
    )
}
