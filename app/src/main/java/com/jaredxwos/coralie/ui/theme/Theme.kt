package com.jaredxwos.coralie.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val HtmlHosterColorScheme = lightColorScheme(
    primary = ContainerBorderPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DDFB),
    onPrimaryContainer = DarkTextPurple,
    secondary = ContainerBorderTeal,
    onSecondary = Color.White,
    secondaryContainer = ButtonShadeTeal,
    onSecondaryContainer = DarkTextTeal,
    tertiary = ContainerBorderPink,
    onTertiary = Color.White,
    tertiaryContainer = VividButtonShadePink,
    onTertiaryContainer = DarkTextPink,
    background = BackgroundAqua,
    onBackground = DarkTextPurple,
    surface = Color.White,
    onSurface = DarkTextPurple,
    surfaceVariant = LightTextAqua,
    onSurfaceVariant = DarkTextTeal,
    outline = ContainerBorderPurple,
    error = Danger,
    onError = Color.White,
    errorContainer = DangerContainer,
    onErrorContainer = OnDangerContainer,
)

@Composable
fun HtmlHosterTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = HtmlHosterColorScheme,
        typography = Typography,
        content = content
    )
}