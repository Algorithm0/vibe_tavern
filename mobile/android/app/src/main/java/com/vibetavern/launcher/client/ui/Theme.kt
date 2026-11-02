package com.vibetavern.launcher.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import com.vibetavern.launcher.R

/** The web app's "coffee" theme (see res/values/colors.xml), as a Material 3 dark scheme. */
object VtColors {
    val Background = Color(0xFF141210)
    val Surface = Color(0xFF1F1D1A)
    val SurfaceRaised = Color(0xFF272420)
    val Border = Color(0xFF36312C)
    val TextPrimary = Color(0xFFD1D0BA)
    val TextSecondary = Color(0xFFA3988F)
    val TextMuted = Color(0xFF756A5F)
    val Accent = Color(0xFFD79628)
    val AccentMid = Color(0xFF95671C)
    val OnAccent = Color(0xFF141210)
    val Destructive = Color(0xFFC65A4D)
    /** Highlight for quoted speech in messages. */
    val Speech = Color(0xFFE6C27A)
}

private val scheme = darkColorScheme(
    primary = VtColors.Accent,
    onPrimary = VtColors.OnAccent,
    primaryContainer = VtColors.AccentMid,
    onPrimaryContainer = VtColors.TextPrimary,
    secondary = VtColors.AccentMid,
    background = VtColors.Background,
    onBackground = VtColors.TextPrimary,
    surface = VtColors.Surface,
    onSurface = VtColors.TextPrimary,
    surfaceVariant = VtColors.SurfaceRaised,
    onSurfaceVariant = VtColors.TextSecondary,
    surfaceContainer = VtColors.Surface,
    surfaceContainerHigh = VtColors.SurfaceRaised,
    outline = VtColors.Border,
    outlineVariant = VtColors.Border,
    error = VtColors.Destructive,
    onError = Color.White,
)

private val inter = FontFamily(Font(R.font.inter_variable))
private val base = Typography()

private fun Typography.withFont(f: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f), bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

@Composable
fun VibeTavernTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = base.withFont(inter), content = content)
}
