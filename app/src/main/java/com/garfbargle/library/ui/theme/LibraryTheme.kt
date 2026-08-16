package com.garfbargle.library.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

val Ink = Color(0xFF090A0B)
val Surface = Color(0xFF111214)
val SurfaceRaised = Color(0xFF191A1D)
val TextPrimary = Color(0xFFF7F7F2)
val TextSecondary = Color(0xFFA1A2A8)
val Acid = Color(0xFFA9FF68)
val Blue = Color(0xFF7BA8FF)

private val LibraryColors = darkColorScheme(
    primary = Acid,
    onPrimary = Ink,
    secondary = Blue,
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF303136),
    error = Color(0xFFFF7474)
)

private val LibraryTypography = Typography().run {
    copy(
        bodyLarge = bodyLarge.merge(TextStyle(fontFamily = FontFamily.SansSerif)),
        bodyMedium = bodyMedium.merge(TextStyle(fontFamily = FontFamily.SansSerif)),
        titleLarge = titleLarge.merge(TextStyle(fontFamily = FontFamily.SansSerif))
    )
}

@Composable
fun LibraryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LibraryColors,
        typography = LibraryTypography,
        content = content
    )
}
