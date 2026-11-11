package win.wintertools.videocatcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Farben: weisse Schrift auf fast schwarzem Grund, Bernsteingelb mit schwarzer
 * Schrift fuer alles, was man antippen soll. Flaechen werden ueber Helligkeits-
 * stufen getrennt statt ueber Rahmen. Alle Text-Kombinationen haben ein
 * Kontrastverhaeltnis von mindestens 10:1.
 */
object Vc {
    val Bg = Color(0xFF0A0B0E)
    val Surface = Color(0xFF15171C)
    val SurfaceHigh = Color(0xFF20242C)
    val SurfaceTop = Color(0xFF2C313C)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFFC9D0DB)
    val Outline = Color(0xFF93A0B3)
    val Accent = Color(0xFFFFD23F)
    val AccentSoft = Color(0xFF3A3212)
    val OnAccent = Color(0xFF0B0B0B)
    val Ok = Color(0xFF4ADE80)
    val Err = Color(0xFFFF8585)
    val ErrSoft = Color(0xFF3A1A1C)
    val Track = Color(0xFF343A47)
}

private val scheme = darkColorScheme(
    primary = Vc.Accent,
    onPrimary = Vc.OnAccent,
    primaryContainer = Vc.Accent,
    onPrimaryContainer = Vc.OnAccent,
    secondary = Vc.Accent,
    onSecondary = Vc.OnAccent,
    secondaryContainer = Vc.Accent,
    onSecondaryContainer = Vc.OnAccent,
    tertiary = Vc.Ok,
    onTertiary = Vc.OnAccent,
    background = Vc.Bg,
    onBackground = Vc.Text,
    surface = Vc.Bg,
    onSurface = Vc.Text,
    surfaceVariant = Vc.Surface,
    onSurfaceVariant = Vc.TextDim,
    surfaceContainerLowest = Vc.Bg,
    surfaceContainerLow = Vc.Surface,
    surfaceContainer = Vc.Surface,
    surfaceContainerHigh = Vc.SurfaceHigh,
    surfaceContainerHighest = Vc.SurfaceTop,
    outline = Vc.Outline,
    outlineVariant = Vc.Track,
    error = Vc.Err,
    onError = Vc.OnAccent,
    scrim = Color(0xCC000000)
)

private val type = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 15.sp),
        bodySmall = t.bodySmall.copy(fontSize = 14.sp),
        labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        labelMedium = t.labelMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
    )
}

@Composable
fun VcTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
