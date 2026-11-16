package app.rescale.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Rescale's own palette: deep ink with an electric violet and a warm amber accent.
 * Used on every Android version (no wallpaper colours) so the app looks the same everywhere.
 */

private val Dark = darkColorScheme(
    primary = Color(0xFFB4A5FF),
    onPrimary = Color(0xFF1D0F5C),
    primaryContainer = Color(0xFF3B2A9E),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFC7C3DC),
    onSecondary = Color(0xFF2F2D42),
    secondaryContainer = Color(0xFF2C2A40),
    onSecondaryContainer = Color(0xFFE4E0F9),
    tertiary = Color(0xFFFFC46B),
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFF5E4100),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF0D0E14),
    onBackground = Color(0xFFE6E4EF),
    surface = Color(0xFF0D0E14),
    onSurface = Color(0xFFE6E4EF),
    surfaceVariant = Color(0xFF2A2B38),
    onSurfaceVariant = Color(0xFFC6C4D4),
    surfaceContainerLowest = Color(0xFF08090E),
    surfaceContainerLow = Color(0xFF12131B),
    surfaceContainer = Color(0xFF171822),
    surfaceContainerHigh = Color(0xFF1F2030),
    surfaceContainerHighest = Color(0xFF2A2B3B),
    outline = Color(0xFF908EA0),
    outlineVariant = Color(0xFF3F3F50),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5536E8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1A0063),
    secondary = Color(0xFF5D5A72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3DFF9),
    onSecondaryContainer = Color(0xFF1A182C),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF2C1800),
    background = Color(0xFFFCFAFF),
    onBackground = Color(0xFF1B1B23),
    surface = Color(0xFFFCFAFF),
    onSurface = Color(0xFF1B1B23),
    surfaceVariant = Color(0xFFE5E1F0),
    onSurfaceVariant = Color(0xFF474656),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F3FD),
    surfaceContainer = Color(0xFFF0EDF8),
    surfaceContainerHigh = Color(0xFFEAE7F3),
    surfaceContainerHighest = Color(0xFFE4E1ED),
    outline = Color(0xFF777587),
    outlineVariant = Color(0xFFC8C5D6),
)

private val Base = Typography()

/** Heavier headings and labels than the Material defaults: bold, confident, still readable. */
private val Type = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun RescaleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Type,
        shapes = Shape,
        content = content,
    )
}
