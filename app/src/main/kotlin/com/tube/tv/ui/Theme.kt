package com.tube.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Accent = Color(0xFFFF3D3D)
val Ink = Color(0xFF0B0B10)
val Panel = Color(0xFF1B1B24)
val TextDim = Color(0xFFB4B4C0)

@Composable
fun TubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Ink,
            surface = Panel,
            onSurface = Color.White,
            onBackground = Color.White,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Ink)) { content() }
    }
}
