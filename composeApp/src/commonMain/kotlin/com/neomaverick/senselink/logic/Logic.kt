package com.neomaverick.senselink.logic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neomaverick.senselink.ble.Advertisement
import com.neomaverick.senselink.ble.Gatt
import com.neomaverick.senselink.ble.Metric
import com.neomaverick.senselink.ble.Reading
import kotlin.math.round

/** Alert rule with hysteresis so a value hovering at the limit doesn't flap on and off. */
data class AlertRule(val metric: Metric, val above: Double? = null, val below: Double? = null, val hysteresis: Double = 1.0)

enum class AlertEdge { Raised, Cleared }

class AlertEngine(var rules: List<AlertRule>) {
    private val active = mutableSetOf<Metric>()

    fun isActive(metric: Metric) = metric in active

    /** Returns Raised/Cleared when the state for this metric changes, otherwise null. */
    fun update(r: Reading): AlertEdge? {
        val rule = rules.firstOrNull { it.metric == r.metric } ?: return null
        val on = r.metric in active
        val breach = (rule.above != null && r.value > rule.above) || (rule.below != null && r.value < rule.below)
        val clear = (rule.above == null || r.value < rule.above - rule.hysteresis) &&
            (rule.below == null || r.value > rule.below + rule.hysteresis)
        return when {
            !on && breach -> AlertEdge.Raised.also { active += r.metric }
            on && clear -> AlertEdge.Cleared.also { active -= r.metric }
            else -> null
        }
    }
}

/** Sensible default limits for the kind of sensor that connected. */
fun defaultRules(device: Advertisement): List<AlertRule> = when {
    Gatt.HEART_RATE_SERVICE in device.services -> listOf(AlertRule(Metric.HeartRate, above = 160.0, hysteresis = 5.0))
    device.name.contains("cold", ignoreCase = true) || device.name.contains("fridge", ignoreCase = true) ->
        listOf(AlertRule(Metric.Temperature, above = 8.0, below = 2.0, hysteresis = 0.5))
    else -> listOf(
        AlertRule(Metric.Temperature, above = 28.0, below = 16.0, hysteresis = 0.5),
        AlertRule(Metric.Humidity, above = 60.0, below = 30.0, hysteresis = 2.0),
    )
}

/** Rolling window for the live chart and the min/avg/max cards. */
class Series(private val capacity: Int = 120) {
    private val values = ArrayDeque<Reading>()

    /** Snapshot state, so Compose redraws whatever reads this series when a reading arrives. */
    private var version by mutableStateOf(0)

    val points: List<Reading> get() = version.let { values.toList() }

    fun add(r: Reading) {
        values.addLast(r)
        while (values.size > capacity) values.removeFirst()
        version++
    }

    val last get() = version.let { values.lastOrNull()?.value }
    val lastAt get() = version.let { values.lastOrNull()?.atMillis }
    val min get() = version.let { values.minOfOrNull { it.value } }
    val max get() = version.let { values.maxOfOrNull { it.value } }
    val average get() = version.let { if (values.isEmpty()) null else values.sumOf { it.value } / values.size }

    /** Change per minute over the window (simple linear regression slope). */
    fun trendPerMinute(): Double? {
        if (version.let { values.size } < 3) return null
        val t0 = values.first().atMillis
        val xs = values.map { (it.atMillis - t0) / 60000.0 }
        val ys = values.map { it.value }
        val mx = xs.average()
        val my = ys.average()
        val num = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val den = xs.sumOf { (it - mx) * (it - mx) }
        return if (den == 0.0) null else num / den
    }
}

/** Heart-rate zones as a share of max HR (220 - age). */
fun hrZone(bpm: Double, age: Int = 35): Pair<Int, String> {
    val pct = bpm / (220 - age)
    return when {
        pct < 0.6 -> 1 to "Recovery"
        pct < 0.7 -> 2 to "Endurance"
        pct < 0.8 -> 3 to "Tempo"
        pct < 0.9 -> 4 to "Threshold"
        else -> 5 to "Max"
    }
}

fun celsiusToF(c: Double) = c * 9 / 5 + 32

fun fmt(v: Double, decimals: Int = 1): String {
    var f = 1.0
    repeat(decimals) { f *= 10 }
    val r = round(v * f) / f
    val s = r.toString()
    return if (decimals == 0) r.toLong().toString() else if ('.' in s) s.padEnd(s.indexOf('.') + decimals + 1, '0') else "$s.${"0".repeat(decimals)}"
}

/** CSV export (RFC 4180 quoting). */
fun toCsv(device: String, readings: List<Reading>): String = buildString {
    append("timestamp_ms,device,metric,value,unit,note\n")
    for (r in readings) {
        append(r.atMillis).append(',')
        append(csvField(device)).append(',')
        append(r.metric.name).append(',')
        append(fmt(r.value, 2)).append(',')
        append(csvField(r.metric.unit)).append(',')
        append(csvField(r.extra ?: "")).append('\n')
    }
}

fun csvField(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
