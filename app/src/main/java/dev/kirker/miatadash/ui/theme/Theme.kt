package dev.kirker.miatadash.ui.theme

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

// Sun-readable light theme: high contrast, deep red accents (Mazda heritage), avoid soft pastels.
private val LightColors = lightColorScheme(
    primary = Color(0xFFB31312),
    onPrimary = Color.White,
    secondary = Color(0xFF1F4E79),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    onSurface = Color(0xFF111111),
)

// Night theme: true blacks, low blue. Designed to not bloom in peripheral vision while driving.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6B6B),
    onPrimary = Color.Black,
    secondary = Color(0xFF7DB6E0),
    background = Color(0xFF000000),
    surface = Color(0xFF0E0E0E),
    onBackground = Color(0xFFE6E6E6),
    onSurface = Color(0xFFE6E6E6),
)

@Composable
fun MiataTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = false,           // Off by default — we want consistent in-cabin colors
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = MiataTypography, content = content)
}
