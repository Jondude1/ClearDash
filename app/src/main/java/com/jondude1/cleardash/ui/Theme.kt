package com.jondude1.cleardash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.jondude1.cleardash.R

/** Same hologram palette as the car screen (and ATLAS): cyan light on near-black teal glass. */
object C {
    val bg = Color(0xFF02070A)
    val card = Color(0xE6041A24)
    val edge = Color(0x9900E5FF)
    val text = Color(0xFFE8FBFF)
    val muted = Color(0xFF7C9CA6)
    val cyan = Color(0xFF00E5FF)
    val green = Color(0xFF7DFFD9)
    val amber = Color(0xFFFFB000)
    val red = Color(0xFFFF3B30)
}

val HoloMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

val HoloSans = FontFamily(
    Font(R.font.sora_light, FontWeight.Light),
    Font(R.font.sora_regular, FontWeight.Normal),
    Font(R.font.sora_semibold, FontWeight.Medium),
    Font(R.font.sora_semibold, FontWeight.SemiBold),
)

@Composable
fun ClearDashTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.sora() = copy(fontFamily = HoloSans)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.cyan,
            onPrimary = Color(0xFF001418),
            secondary = C.green,
            background = C.bg,
            onBackground = C.text,
            surface = Color(0xFF041A24),
            onSurface = C.text,
            surfaceVariant = Color(0xFF0A1F29),
            onSurfaceVariant = C.muted,
            surfaceContainerHigh = Color(0xFF0A1F29),
            outline = C.edge,
            error = C.red,
        ),
        typography = Typography(
            displayLarge = base.displayLarge.sora(), displayMedium = base.displayMedium.sora(), displaySmall = base.displaySmall.sora(),
            headlineLarge = base.headlineLarge.sora(), headlineMedium = base.headlineMedium.sora(), headlineSmall = base.headlineSmall.sora(),
            titleLarge = base.titleLarge.sora(), titleMedium = base.titleMedium.sora(), titleSmall = base.titleSmall.sora(),
            bodyLarge = base.bodyLarge.sora(), bodyMedium = base.bodyMedium.sora(), bodySmall = base.bodySmall.sora(),
            labelLarge = base.labelLarge.copy(fontFamily = HoloMono), labelMedium = base.labelMedium.copy(fontFamily = HoloMono),
            labelSmall = base.labelSmall.copy(fontFamily = HoloMono),
        ),
        content = content,
    )
}
