package com.habitlair.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.habitlair.app.data.Accent
import com.habitlair.app.data.AppSettings
import com.habitlair.app.data.ThemeMode

val LocalCompact = staticCompositionLocalOf { false }
val LocalAnim = staticCompositionLocalOf { true }

private fun accentScheme(a: Accent, dark: Boolean): ColorScheme {
    val p = Color(a.argb)
    return if (dark) darkColorScheme(
        primary = p, onPrimary = Color(0xFF1B1B1B),
        primaryContainer = lerp(Color.Black, p, 0.38f), onPrimaryContainer = lerp(Color.White, p, 0.25f),
        secondary = lerp(p, Color(0xFFB0B0B0), 0.6f), secondaryContainer = lerp(Color(0xFF2A2A2A), p, 0.25f),
        onSecondaryContainer = lerp(Color.White, p, 0.2f), tertiary = lerp(p, Color(0xFF7CB342), 0.5f),
        background = Color(0xFF121212), onBackground = Color(0xFFE6E1E1), surface = Color(0xFF121212), onSurface = Color(0xFFE6E1E1),
        surfaceVariant = Color(0xFF2B2B2B), onSurfaceVariant = Color(0xFFC9C4C4),
        surfaceContainerLowest = Color(0xFF0D0D0D), surfaceContainerLow = Color(0xFF181818), surfaceContainer = Color(0xFF1E1E1E),
        surfaceContainerHigh = Color(0xFF262626), surfaceContainerHighest = Color(0xFF303030),
        outline = Color(0xFF948F8F), outlineVariant = Color(0xFF3F3B3B)
    ) else lightColorScheme(
        primary = lerp(p, Color.Black, 0.12f), onPrimary = Color.White,
        primaryContainer = lerp(Color.White, p, 0.22f), onPrimaryContainer = lerp(Color.Black, p, 0.35f),
        secondary = lerp(p, Color(0xFF606060), 0.6f), secondaryContainer = lerp(Color.White, p, 0.16f),
        onSecondaryContainer = lerp(Color.Black, p, 0.35f), tertiary = lerp(p, Color(0xFF3B7A00), 0.5f),
        background = Color(0xFFFAF8F8), surface = Color(0xFFFAF8F8),
        surfaceVariant = Color(0xFFE7E1E1), onSurfaceVariant = Color(0xFF4D4545),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F2F2), surfaceContainer = Color(0xFFF0EDED),
        surfaceContainerHigh = Color(0xFFEAE6E6), surfaceContainerHighest = Color(0xFFE4E0E0),
        outline = Color(0xFF857D7D), outlineVariant = Color(0xFFCFC6C6)
    )
}

@Composable
fun HabitLairTheme(s: AppSettings, content: @Composable () -> Unit) {
    val dark = when (s.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val ctx = LocalContext.current
    var scheme = if (s.dynamicColor && Build.VERSION.SDK_INT >= 31)
        (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
    else accentScheme(s.accent, dark)
    if (dark && s.themeMode == ThemeMode.AMOLED) scheme = scheme.copy(
        background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF080808), surfaceContainer = Color(0xFF0E0E0E),
        surfaceContainerHigh = Color(0xFF151515), surfaceContainerHighest = Color(0xFF1D1D1D), surfaceVariant = Color(0xFF1D1D1D)
    )
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalCompact provides s.compact, LocalAnim provides s.animations) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
