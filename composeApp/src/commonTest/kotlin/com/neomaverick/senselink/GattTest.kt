package com.neomaverick.senselink

import com.neomaverick.senselink.ble.encodeHeartRate
import com.neomaverick.senselink.ble.encodeSint16
import com.neomaverick.senselink.ble.encodeUint16
import com.neomaverick.senselink.ble.parseBattery
import com.neomaverick.senselink.ble.parseHeartRate
import com.neomaverick.senselink.ble.parseHumidity
import com.neomaverick.senselink.ble.parseTemperatureC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GattTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun heartRate8bitWithContact() {
        val hr = parseHeartRate(bytes(0x06, 72))!!
        assertEquals(72, hr.bpm)
        assertEquals(true, hr.sensorContact)
        assertNull(hr.energyKj)
    }

    @Test fun heartRate16bitWithEnergyAndRr() {
        // flags: 16-bit, contact supported but not detected, energy, RR; bpm 300; energy 0x0102; RR 1024/1024 s and 512/1024 s
        val hr = parseHeartRate(bytes(0x01 or 0x04 or 0x08 or 0x10, 0x2C, 0x01, 0x02, 0x01, 0x00, 0x04, 0x00, 0x02))!!
        assertEquals(300, hr.bpm)
        assertEquals(false, hr.sensorContact)
        assertEquals(258, hr.energyKj)
        assertEquals(listOf(1000, 500), hr.rrIntervalsMs)
    }

    @Test fun heartRateRejectsTruncatedPackets() {
        assertNull(parseHeartRate(bytes(0x01, 0x2C)))
        assertNull(parseHeartRate(bytes(0x00)))
    }

    @Test fun encoderRoundTrip() {
        for (bpm in listOf(40, 120, 255, 256, 310)) {
            val hr = parseHeartRate(encodeHeartRate(bpm, listOf(800)))!!
            assertEquals(bpm, hr.bpm)
            assertEquals(800, hr.rrIntervalsMs.single())
        }
    }

    @Test fun temperatureIsSigned() {
        assertEquals(21.5, parseTemperatureC(encodeSint16(21.5)))
        assertEquals(-18.25, parseTemperatureC(encodeSint16(-18.25)))
        assertNull(parseTemperatureC(bytes(0x00, 0x80))) // 0x8000 = unknown
    }

    @Test fun humidityAndBattery() {
        assertEquals(45.67, parseHumidity(encodeUint16(45.67)))
        assertNull(parseHumidity(bytes(0xFF, 0xFF)))
        assertEquals(87, parseBattery(bytes(87)))
        assertEquals(100, parseBattery(bytes(130)))
        assertNull(parseBattery(ByteArray(0)))
    }

    @Test fun littleEndian() {
        val t = parseTemperatureC(bytes(0x34, 0x12))!! // 0x1234 = 4660 -> 46.60 °C
        assertTrue(kotlin.math.abs(t - 46.60) < 1e-9)
    }
}
