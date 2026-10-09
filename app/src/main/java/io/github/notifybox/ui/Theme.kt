package io.github.notifybox.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.notifybox.data.DEFAULT_THEME_RGB

// 色值、字体角色、Shapes 来自邻近 DaoDianLa 项目的 Theme.kt。
// Serif 只声明系统字体族，实际中文回退字体由设备决定。
private data class NotifyPalette(
    val background: Color = Color(0xFFF1F2FF), val ink: Color = Color(0xFF1D2A50),
    val inkSoft: Color = Color(0xFF40548B), val accent: Color = Color(0xFF4868AE),
    val tint: Color = Color(0xFFDDE5FF), val muted: Color = Color(0xFF667399),
    val line: Color = Color(0xFFE3E6F5), val disabled: Color = Color(0xFFF0F1F8),
    val hero: Color = Color(0xFFB7C8FA), val secondary: Color = Color(0xFFB9C8FF),
    val tertiaryTint: Color = Color(0xFFE8EDFC), val surfaceLow: Color = Color(0xFFF7F8FF),
    val surfaceHigh: Color = Color(0xFFEEF1FC),
)
private val OriginalPalette = NotifyPalette()
private val LocalNotifyPalette = staticCompositionLocalOf { OriginalPalette }

fun themeAccentColor(rgb: Int): Color {
    if (rgb == DEFAULT_THEME_RGB) return OriginalPalette.accent
    var color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))
    // 主色同时用于浅底上的文字和白字按钮，避免自选浅色导致不可读。
    while (1.05f / (color.luminance() + .05f) < 5.5f) color = lerp(color, Color.Black, .05f)
    return color
}
private fun paletteFor(rgb: Int): NotifyPalette {
    if (rgb == DEFAULT_THEME_RGB) return OriginalPalette
    val accent = themeAccentColor(rgb)
    fun tint(amount: Float) = lerp(Color.White, accent, amount)
    return NotifyPalette(
        background = tint(.06f), ink = lerp(Color(0xFF182330), accent, .13f),
        inkSoft = lerp(Color(0xFF384452), accent, .20f), accent = accent, tint = tint(.16f),
        muted = lerp(Color(0xFF56616F), accent, .10f), line = tint(.14f), disabled = tint(.05f),
        hero = tint(.42f), secondary = tint(.30f), tertiaryTint = tint(.09f),
        surfaceLow = tint(.025f), surfaceHigh = tint(.07f),
    )
}
object NotifyColors {
    val background: Color @Composable @ReadOnlyComposable get() = LocalNotifyPalette.current.background
    val ink: Color @Composable @ReadOnlyComposable get() = LocalNotifyPalette.current.ink
    val inkSoft: Color @Composable @ReadOnlyComposable get() = LocalNotifyPalette.current.inkSoft
    val blue: Color @Composable @ReadOnlyComposable get() = LocalNotifyPalette.current.accent
    val blueTint: Color @Composable @ReadOnlyComposable get() = LocalNotifyPalette.current.tint
    val muted: Color @Composable @ReadOnlyComposable get() = LocalNotifyPalette.current.muted
    val line: Color @Composable @ReadOnlyComposable get() = LocalNotifyPalette.current.line
    val disabled: Color @Composable @ReadOnlyComposable get() = LocalNotifyPalette.current.disabled
    val hero: Color @Composable @ReadOnlyComposable get() = LocalNotifyPalette.current.hero
    val warningTint = Color(0xFFFFF1D2)
    val warning = Color(0xFF94651C)
}
private fun colorScheme(p: NotifyPalette) = lightColorScheme(
    primary = p.accent, onPrimary = Color.White,
    primaryContainer = p.tint, onPrimaryContainer = p.ink,
    secondary = p.secondary, onSecondary = p.ink,
    secondaryContainer = p.tint, onSecondaryContainer = p.inkSoft,
    tertiary = p.inkSoft, onTertiary = Color.White,
    tertiaryContainer = p.tertiaryTint, onTertiaryContainer = p.ink,
    background = p.background, onBackground = p.ink,
    surface = Color.White, onSurface = p.ink,
    surfaceVariant = p.disabled, onSurfaceVariant = p.muted,
    surfaceContainerLowest = Color.White, surfaceContainerLow = p.surfaceLow,
    surfaceContainer = Color.White, surfaceContainerHigh = p.surfaceHigh,
    surfaceContainerHighest = p.tint,
    outline = p.line, outlineVariant = p.line,
    inverseSurface = p.ink, inverseOnSurface = Color.White,
    inversePrimary = p.tint, surfaceTint = p.accent,
    error = Color(0xFFAD3947), onError = Color.White,
    errorContainer = Color(0xFFFCECEF), onErrorContainer = Color(0xFF782D38),
)
private val NotifyTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.SansSerif),
    )
}
@Composable
fun NotifyBoxTheme(accentRgb: Int = DEFAULT_THEME_RGB, content: @Composable () -> Unit) {
    val palette = remember(accentRgb) { paletteFor(accentRgb) }
    val scheme = remember(palette) { colorScheme(palette) }
    CompositionLocalProvider(LocalNotifyPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = NotifyTypography,
            shapes = Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp)),
            content = content)
    }
}
