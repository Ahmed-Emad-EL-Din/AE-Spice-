package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = ElegantBlueAccent,
    onPrimary = ElegantBlueContrast,
    primaryContainer = ActiveButtonBg,
    onPrimaryContainer = ActiveButtonText,
    secondary = ElegantBlueAccent,
    onSecondary = ElegantBlueContrast,
    background = BgDark,
    onBackground = TextPrimary,
    surface = BgDark,
    onSurface = TextPrimary,
    surfaceVariant = ActiveButtonBg,
    onSurfaceVariant = TextSecondary,
    outline = BorderColor,
    outlineVariant = BorderColor,
    error = WaveformPink
  )

private val LightColorScheme = DarkColorScheme // Force dark theme layout for Elegant Dark experience

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // default to Elegant Dark
  dynamicColor: Boolean = false, // disable dynamic color so that custom theme colors stand out
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else DarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
