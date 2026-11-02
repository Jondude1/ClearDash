package com.jondude1.cleardash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val bg = Color(0xFF06080B)
    val card = Color(0xFF0D1218)
    val edge = Color(0xFF18212B)
    val text = Color(0xFFF2F6FA)
    val muted = Color(0xFF7F8C9B)
    val cyan = Color(0xFF39D0FF)
    val green = Color(0xFF3DDC97)
    val amber = Color(0xFFFFB547)
    val red = Color(0xFFFF5468)
}

@Composable
fun ClearDashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.cyan,
            onPrimary = Color(0xFF00141C),
            secondary = C.green,
            background = C.bg,
            onBackground = C.text,
            surface = C.card,
            onSurface = C.text,
            surfaceVariant = Color(0xFF141B23),
            onSurfaceVariant = C.muted,
            surfaceContainerHigh = Color(0xFF141B23),
            outline = C.edge,
            error = C.red,
        ),
        content = content,
    )
}
