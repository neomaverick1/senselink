package com.neomaverick.senselink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Paper = Color(0xFFF3F6F5)
val Ink = Color(0xFF0F1B19)
val Muted = Color(0xFF6A7A76)
val Teal = Color(0xFF0E7C66)
val TealSoft = Color(0xFFDDEFEA)
val Coral = Color(0xFFFF5A47)
val CoralSoft = Color(0xFFFFE6E2)
val Sky = Color(0xFF2F80ED)
val Card = Color.White
val Line = Color(0xFFE2E8E6)

fun metricColor(m: com.neomaverick.senselink.ble.Metric) = when (m) {
    com.neomaverick.senselink.ble.Metric.HeartRate -> Coral
    com.neomaverick.senselink.ble.Metric.Temperature -> Color(0xFFF2994A)
    com.neomaverick.senselink.ble.Metric.Humidity -> Sky
    com.neomaverick.senselink.ble.Metric.Battery -> Teal
}

@Composable
fun SenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal,
            onPrimary = Color.White,
            secondary = Coral,
            background = Paper,
            surface = Card,
            onSurface = Ink,
            onBackground = Ink,
        ),
        content = content,
    )
}
