package me.dominaezzz.chitchat.ui

import androidx.compose.desktop.DesktopMaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LightColorPalette = lightColors(
    primary = Purple700,
    primaryVariant = Purple800,
    onPrimary = Color.White,
    secondary = Color.White,
    onSecondary = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    error = Red800,
    onError = Color.White
)

val DarkColorPalette = darkColors(
    primary = Purple300,
    primaryVariant = Purple600,
    onPrimary = Color.Black,
    secondary = Color.White,
    onSecondary = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    error = Red300,
    onError = Color.Black
)

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
