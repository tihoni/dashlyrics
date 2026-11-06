package com.autolyrics.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BackgroundDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
val SpotifyGreen = Color(0xFF1DB954)
val LyricActiveWhite = Color(0xFFFFFFFF)
val LyricMutedGray = Color(0xFF666666)

private val AutoLyricsColorScheme = darkColorScheme(
    primary = SpotifyGreen,
    secondary = SpotifyGreen,
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = LyricActiveWhite,
    onSurface = LyricActiveWhite
)

@Composable
fun AutoLyricsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AutoLyricsColorScheme,
        content = content
    )
}
