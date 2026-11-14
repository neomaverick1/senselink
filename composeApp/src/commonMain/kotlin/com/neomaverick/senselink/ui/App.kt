package com.neomaverick.senselink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neomaverick.senselink.AppState
import com.neomaverick.senselink.ble.Advertisement
import com.neomaverick.senselink.ble.Gatt
import com.neomaverick.senselink.ble.LinkState
import com.neomaverick.senselink.ble.Metric
import com.neomaverick.senselink.logic.AlertEdge
import com.neomaverick.senselink.logic.celsiusToF
import com.neomaverick.senselink.logic.fmt
import com.neomaverick.senselink.logic.hrZone

@Composable
fun App(state: AppState? = null) {
    val scope = rememberCoroutineScope()
    val s = state ?: remember { AppState(scope) }
    SenseTheme {
        Box(Modifier.fillMaxSize().background(Paper).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                AnimatedContent(s.connection != null || s.link == LinkState.Connecting, label = "screen") { connected ->
                    if (connected) DeviceScreen(s) else ScanScreen(s)
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, fg: Color, bg: Color, icon: ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SignalBars(bars: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(
                Modifier.width(4.dp).height((4 + i * 4).dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i <= bars) Teal else Line),
            )
        }
    }
}

@Composable
fun ScanScreen(s: AppState) {
    LaunchedEffect(s.useSimulator) { s.startScan() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Teal),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Bluetooth, null, tint = Color.White) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("SenseLink", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Ink)
                    Text("BLE sensor companion", color = Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(4.dp),
            ) {
                listOf(true to "Simulator", false to "Bluetooth").forEach { (sim, label) ->
                    val on = s.useSimulator == sim
                    val enabled = sim || s.real != null
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) Teal else Color.Transparent)
                            .clickable(enabled = enabled) { s.useSimulator = sim }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label + if (!enabled) " (n/a)" else "",
                            color = if (on) Color.White else if (enabled) Ink else Muted,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nearby devices", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Ink, modifier = Modifier.weight(1f))
                if (s.scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Teal)
                    Spacer(Modifier.width(8.dp))
                    Text("Scanning", color = Teal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                } else {
                    Text("Scan", color = Teal, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { s.startScan() })
                }
            }
            s.error?.let { Text(it, color = Coral, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(10.dp))
        }
        items(s.found.values.sortedByDescending { it.rssi }, key = { it.id }) { d -> DeviceRow(d) { s.connect(d) } }
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                "Reads standard Bluetooth GATT profiles: Heart Rate (0x180D), Environmental Sensing (0x181A) and Battery (0x180F). " +
                    "The simulator sends the same bytes real sensors do.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun DeviceRow(d: Advertisement, onConnect: () -> Unit) {
    val hr = Gatt.HEART_RATE_SERVICE in d.services
    Row(
        Modifier.padding(vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)
            .border(1.dp, Line, RoundedCornerShape(18.dp)).clickable(onClick = onConnect).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (hr) CoralSoft else TealSoft),
            contentAlignment = Alignment.Center,
        ) { Icon(if (hr) Icons.Rounded.Favorite else Icons.Rounded.DeviceThermostat, null, tint = if (hr) Coral else Teal) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${d.kind} · ${d.services.joinToString(" ") { "0x" + Gatt.short(it) }}",
                color = Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            SignalBars(d.bars)
            Text("${d.rssi} dBm", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun DeviceScreen(s: AppState) {
    val c = s.connection
    @Suppress("UNUSED_VARIABLE") val tick = s.tick
    var selected by remember { mutableStateOf<Metric?>(null) }
    val metrics = s.series.keys.filter { it != Metric.Battery }.sortedBy { it.ordinal }
    val current = selected?.takeIf { it in metrics } ?: metrics.firstOrNull()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    "Disconnect",
                    tint = Ink,
                    modifier = Modifier.clip(CircleShape).clickable { s.disconnect() }.padding(6.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(c?.device?.name ?: "Connecting…", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c?.device?.kind ?: "", color = Muted, fontSize = 12.sp)
                }
                val linkColor = if (s.link == LinkState.Connected) Teal else Coral
                Pill(s.link.name, linkColor, if (s.link == LinkState.Connected) TealSoft else CoralSoft)
            }
            Spacer(Modifier.height(16.dp))
            if (current == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Teal)
                }
            }
        }
        if (current != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    metrics.forEach { m ->
                        MetricCard(s, m, selected = m == current, modifier = Modifier.weight(1f)) { selected = m }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ChartCard(s, current)
                Spacer(Modifier.height(12.dp))
                InfoRow(s)
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = Ink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Alerts", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Ink)
                }
                Spacer(Modifier.height(6.dp))
                if (s.alerts.isEmpty()) {
                    val limits = s.alertEngine.rules.joinToString(" · ") { r ->
                        r.metric.label + " " + listOfNotNull(r.below?.let { "≥ ${fmt(it, 0)}" }, r.above?.let { "≤ ${fmt(it, 0)}" }).joinToString(" and ") + " " + r.metric.unit
                    }
                    Text("All readings in range. Limits: $limits", color = Muted, fontSize = 13.sp)
                }
            }
            items(s.alerts.take(6)) { a ->
                val raised = a.edge == AlertEdge.Raised
                val ago = ((s.lastReadingAt - a.atMillis) / 1000).coerceAtLeast(0)
                Row(
                    Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (raised) CoralSoft else TealSoft).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (raised) "Above limit: " else "Back to normal: ") + "${a.metric.label} ${display(s, a.metric, a.value)}",
                        color = if (raised) Coral else Teal,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                    )
                    Text(if (ago < 60) "${ago}s ago" else "${ago / 60}m ago", color = Muted, fontSize = 12.sp)
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { s.exportCsv() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink),
                ) {
                    Icon(Icons.Rounded.IosShare, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export ${s.readingCount()} readings (CSV)", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun display(s: AppState, m: Metric, v: Double): String = when (m) {
    Metric.Temperature -> if (s.fahrenheit) "${fmt(celsiusToF(v))} °F" else "${fmt(v)} °C"
    Metric.HeartRate, Metric.Battery -> "${fmt(v, 0)} ${m.unit}"
    else -> "${fmt(v)} ${m.unit}"
}

@Composable
private fun MetricCard(s: AppState, m: Metric, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val series = s.series[m] ?: return
    val color = metricColor(m)
    val alert = s.alertEngine.isActive(m)
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(if (alert) CoralSoft else Card)
            .border(if (selected) 2.dp else 1.dp, if (selected) color else Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(m.label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text(series.last?.let { display(s, m, it) } ?: "–", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Ink)
        val trend = series.trendPerMinute()
        val icon = when {
            trend == null || kotlin.math.abs(trend) < 0.2 -> Icons.Rounded.TrendingFlat
            trend > 0 -> Icons.Rounded.TrendingUp
            else -> Icons.Rounded.TrendingDown
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(trend?.let { "${if (it >= 0) "+" else ""}${fmt(it)}/min" } ?: "steady", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ChartCard(s: AppState, m: Metric) {
    val series = s.series[m] ?: return
    val limits = s.alertEngine.rules.filter { it.metric == m }.flatMap { listOfNotNull(it.above, it.below) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${m.label}, live", fontWeight = FontWeight.ExtraBold, color = Ink, modifier = Modifier.weight(1f))
            Text("last ${series.points.size} readings", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        LiveChart(series.points, metricColor(m), limits)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Min" to series.min, "Avg" to series.average, "Max" to series.max).forEach { (label, v) ->
                Column {
                    Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(v?.let { display(s, m, it) } ?: "–", color = Ink, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (m == Metric.HeartRate) {
            series.last?.let { bpm ->
                val (zone, name) = hrZone(bpm)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (z in 1..5) {
                        Box(
                            Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (z <= zone) Coral.copy(alpha = 0.35f + z * 0.13f) else Line),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Zone $zone · $name", color = Coral, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InfoRow(s: AppState) {
    val battery = s.series[Metric.Battery]?.last
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (battery != null) Pill("Battery ${fmt(battery, 0)}%", Teal, TealSoft)
        if (s.connection?.device?.simulated == true) Pill("Simulated", Muted, Line, Icons.Rounded.Science)
        else Pill("Bluetooth LE", Sky, Color(0xFFE3EEFD), Icons.Rounded.BluetoothSearching)
        Box(Modifier.clip(RoundedCornerShape(50)).clickable { s.fahrenheit = !s.fahrenheit }) {
            Pill(if (s.fahrenheit) "°F" else "°C", Ink, Line)
        }
    }
}
