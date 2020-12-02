package me.dominaezzz.chitchat.ui

import androidx.compose.desktop.DesktopMaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable

val LightColorPalette = lightColors()

val DarkColorPalette = darkColors()

@Composable
fun ChitChatTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        DarkColorPalette
    } else {
        LightColorPalette
    }

    DesktopMaterialTheme(
            colors = colors,
            typography = typography,
            shapes = shapes,
            content = content
    )
}
