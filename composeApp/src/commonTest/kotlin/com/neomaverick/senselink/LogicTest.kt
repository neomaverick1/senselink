package com.neomaverick.senselink

import com.neomaverick.senselink.ble.Advertisement
import com.neomaverick.senselink.ble.Gatt
import com.neomaverick.senselink.ble.Metric
import com.neomaverick.senselink.ble.Reading
import com.neomaverick.senselink.ble.SimulatedBleClient
import com.neomaverick.senselink.logic.AlertEdge
import com.neomaverick.senselink.logic.AlertEngine
import com.neomaverick.senselink.logic.AlertRule
import com.neomaverick.senselink.logic.Series
import com.neomaverick.senselink.logic.celsiusToF
import com.neomaverick.senselink.logic.fmt
import com.neomaverick.senselink.logic.hrZone
import com.neomaverick.senselink.logic.toCsv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogicTest {
    private fun r(v: Double, t: Long = 0, m: Metric = Metric.Temperature) = Reading(m, v, t)

    @Test fun alertsUseHysteresis() {
        val e = AlertEngine(listOf(AlertRule(Metric.Temperature, above = 8.0, below = 2.0, hysteresis = 0.5)))
        assertNull(e.update(r(5.0)))
        assertEquals(AlertEdge.Raised, e.update(r(8.2)))
        assertNull(e.update(r(8.4)))
        assertNull(e.update(r(7.8))) // inside the hysteresis band: still active
        assertTrue(e.isActive(Metric.Temperature))
        assertEquals(AlertEdge.Cleared, e.update(r(7.4)))
        assertEquals(AlertEdge.Raised, e.update(r(1.5)))
        assertEquals(AlertEdge.Cleared, e.update(r(3.0)))
        assertNull(e.update(r(99.0, m = Metric.Battery)))
    }

    @Test fun seriesStatsAndTrend() {
        val s = Series(capacity = 5)
        (1..8).forEach { s.add(r(it.toDouble(), t = it * 60_000L)) }
        assertEquals(5, s.points.size)
        assertEquals(4.0, s.min)
        assertEquals(8.0, s.max)
        assertEquals(6.0, s.average)
        assertEquals(1.0, s.trendPerMinute()!!, 1e-9)
    }

    @Test fun zonesUnitsAndFormatting() {
        assertEquals(1, hrZone(90.0, age = 35).first)
        assertEquals(4, hrZone(160.0, age = 35).first)
        assertEquals(5, hrZone(180.0, age = 35).first)
        assertEquals(98.6, celsiusToF(37.0), 1e-9)
        assertEquals("21.5", fmt(21.5))
        assertEquals("4.10", fmt(4.1, 2))
        assertEquals("72", fmt(72.4, 0))
    }

    @Test fun csvIsQuoted() {
        val csv = toCsv("Strap, \"HR\"", listOf(Reading(Metric.HeartRate, 72.0, 1000, "RR 833 ms")))
        val lines = csv.trim().lines()
        assertEquals("timestamp_ms,device,metric,value,unit,note", lines[0])
        assertEquals("1000,\"Strap, \"\"HR\"\"\",HeartRate,72.00,bpm,RR 833 ms", lines[1])
    }

    @Test fun rssiBars() {
        assertEquals(4, Advertisement("a", "a", -50, emptyList()).bars)
        assertEquals(1, Advertisement("a", "a", -85, emptyList()).bars)
        assertEquals("Heart rate monitor", Advertisement("a", "a", -50, listOf(Gatt.HEART_RATE_SERVICE)).kind)
    }

    @Test fun simulatorSendsParsableReadings() = runTest {
        val sim = SimulatedBleClient(clock = { 0L }, tickMs = 1)
        val found = sim.scan().take(3).toList()
        assertEquals(listOf("sim-hr", "sim-env", "sim-fridge"), found.map { it.id })
        val hr = sim.connect(found[0]).readings.first()
        assertEquals(Metric.HeartRate, hr.metric)
        assertTrue(hr.value in 60.0..160.0)
        val env = sim.connect(found[1]).readings.take(2).toList()
        assertEquals(listOf(Metric.Temperature, Metric.Humidity), env.map { it.metric })
        assertTrue(env[0].value in 19.0..24.0)
    }
}

class RulesTest {
    @Test fun rulesFitTheSensor() {
        val hr = com.neomaverick.senselink.logic.defaultRules(Advertisement("1", "Strap", -50, listOf(Gatt.HEART_RATE_SERVICE)))
        assertEquals(listOf(Metric.HeartRate), hr.map { it.metric })
        val fridge = com.neomaverick.senselink.logic.defaultRules(Advertisement("2", "Cold Chain Tag", -50, listOf(Gatt.ENVIRONMENTAL_SENSING_SERVICE)))
        assertEquals(8.0, fridge.single().above)
        val room = com.neomaverick.senselink.logic.defaultRules(Advertisement("3", "Room", -50, listOf(Gatt.ENVIRONMENTAL_SENSING_SERVICE)))
        assertEquals(setOf(Metric.Temperature, Metric.Humidity), room.map { it.metric }.toSet())
    }
}
