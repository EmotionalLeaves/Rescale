package app.rescale.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Dark = darkColorScheme(
    primary = Color(0xFF8FB3E0),
    onPrimary = Color(0xFF0B1D33),
    primaryContainer = Color(0xFF29405E),
    secondary = Color(0xFFB7C7DB),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceContainer = Color(0xFF1B2128),
)

private val Light = lightColorScheme(
    primary = Color(0xFF345B8A),
    primaryContainer = Color(0xFFD3E3FF),
    secondary = Color(0xFF545F70),
)

@Composable
fun RescaleTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
