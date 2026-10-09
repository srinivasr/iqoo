package com.dhrashta.x.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private data class Palette(
    val canvas: Color,
    val surface: Color,
    val raised: Color,
    val ink: Color,
    val mutedInk: Color,
    val accent: Color,
    val accentSoft: Color,
    val safe: Color,
    val safeSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val info: Color,
    val infoSoft: Color,
    val line: Color,
    val danger: Color,
    val dangerSoft: Color,
    val critical: Color,
)

private val DarkPalette = Palette(
    canvas = Color(0xFF0B0D11), surface = Color(0xFF191C22), raised = Color(0xFF242830),
    ink = Color(0xFFF6F6F3), mutedInk = Color(0xFFBDC2CA),
    accent = Color(0xFFFFA34D), accentSoft = Color(0xFF332419),
    safe = Color(0xFF75DEB0), safeSoft = Color(0xFF192D25),
    amber = Color(0xFFF6C573), amberSoft = Color(0xFF332819),
    info = Color(0xFF94C8F5), infoSoft = Color(0xFF1A2B3D),
    line = Color(0xFF30343C), danger = Color(0xFFFF8F99), dangerSoft = Color(0xFF392126),
    critical = Color(0xFFA82E42),
)

private val LightPalette = Palette(
    canvas = Color(0xFFF7F5F1), surface = Color(0xFFFFFFFF), raised = Color(0xFFF0ECE6),
    ink = Color(0xFF191B20), mutedInk = Color(0xFF525860),
    accent = Color(0xFF9B4405), accentSoft = Color(0xFFF8E9D8),
    safe = Color(0xFF146B49), safeSoft = Color(0xFFE7F4EB),
    amber = Color(0xFF875000), amberSoft = Color(0xFFFFF1DB),
    info = Color(0xFF245B83), infoSoft = Color(0xFFE7F1F8),
    line = Color(0xFFE3DFD8), danger = Color(0xFFA62E3B), dangerSoft = Color(0xFFFBE9EA),
    critical = Color(0xFFA82E42),
)

private val LocalPalette = staticCompositionLocalOf { DarkPalette }

val Canvas: Color @Composable get() = LocalPalette.current.canvas
val Surface: Color @Composable get() = LocalPalette.current.surface
val SurfaceRaised: Color @Composable get() = LocalPalette.current.raised
val Ink: Color @Composable get() = LocalPalette.current.ink
val MutedInk: Color @Composable get() = LocalPalette.current.mutedInk
val Accent: Color @Composable get() = LocalPalette.current.accent
val AccentSoft: Color @Composable get() = LocalPalette.current.accentSoft
val Forest: Color @Composable get() = LocalPalette.current.safe
val ForestSoft: Color @Composable get() = LocalPalette.current.safeSoft
val Amber: Color @Composable get() = LocalPalette.current.amber
val AmberSoft: Color @Composable get() = LocalPalette.current.amberSoft
val Info: Color @Composable get() = LocalPalette.current.info
val InfoSoft: Color @Composable get() = LocalPalette.current.infoSoft
val Line: Color @Composable get() = LocalPalette.current.line
val Danger: Color @Composable get() = LocalPalette.current.danger
val DangerSoft: Color @Composable get() = LocalPalette.current.dangerSoft
val Critical: Color @Composable get() = LocalPalette.current.critical

private val DarkColors = darkColorScheme(
    primary = DarkPalette.accent, onPrimary = DarkPalette.canvas,
    primaryContainer = DarkPalette.accentSoft, onPrimaryContainer = DarkPalette.ink,
    background = DarkPalette.canvas, onBackground = DarkPalette.ink,
    surface = DarkPalette.surface, onSurface = DarkPalette.ink,
    surfaceVariant = DarkPalette.raised, onSurfaceVariant = DarkPalette.mutedInk,
    outline = DarkPalette.line, error = DarkPalette.danger,
)

private val LightColors = lightColorScheme(
    primary = LightPalette.accent, onPrimary = LightPalette.surface,
    primaryContainer = LightPalette.accentSoft, onPrimaryContainer = LightPalette.ink,
    background = LightPalette.canvas, onBackground = LightPalette.ink,
    surface = LightPalette.surface, onSurface = LightPalette.ink,
    surfaceVariant = LightPalette.raised, onSurfaceVariant = LightPalette.mutedInk,
    outline = LightPalette.line, error = LightPalette.danger,
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
)

@Composable
fun DhrashtaTheme(mode: AppThemeMode, content: @Composable () -> Unit) {
    val palette = if (mode == AppThemeMode.Dark) DarkPalette else LightPalette
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = palette.canvas.toArgb()
            window.navigationBarColor = palette.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = mode == AppThemeMode.Light
                isAppearanceLightNavigationBars = mode == AppThemeMode.Light
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    MaterialTheme(colorScheme = if (mode == AppThemeMode.Dark) DarkColors else LightColors, typography = AppTypography) {
        CompositionLocalProvider(LocalPalette provides palette, LocalContentColor provides palette.ink) {
            content()
        }
    }
}
