package com.jondude1.cleardash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Same palette as the car screen: charcoal, warm silver, one Soul Red accent. */
object C {
    val bg = Color(0xFF07080A)
    val card = Color(0xFF131518)
    val edge = Color(0xFF26282D)
    val text = Color(0xFFF4F1EC)
    val muted = Color(0xFF9A968F)
    val cyan = Color(0xFFE0283A) // accent (Soul Red) — name kept for existing call sites
    val green = Color(0xFF8FC9A3)
    val amber = Color(0xFFF2A93B)
    val red = Color(0xFFFF4D4D)
}

@Composable
fun ClearDashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.cyan,
            onPrimary = Color.White,
            secondary = C.green,
            background = C.bg,
            onBackground = C.text,
            surface = C.card,
            onSurface = C.text,
            surfaceVariant = Color(0xFF1A1C20),
            onSurfaceVariant = C.muted,
            surfaceContainerHigh = Color(0xFF1A1C20),
            outline = C.edge,
            error = C.red,
        ),
        content = content,
    )
}
