package com.neomaverick.senselink.ble

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sensors you can use without hardware. Each one produces the exact GATT bytes a real device
 * would notify, which then go through the same parsers as real Bluetooth data.
 */
class SimulatedBleClient(
    private val clock: () -> Long,
    private val tickMs: Long = 1000,
    private val random: Random = Random(7),
) : BleClient {
    override val name = "Simulator"
    override val isAvailable = true

    val devices = listOf(
        Advertisement("sim-hr", "Pulse Strap HR (sim)", -52, listOf(Gatt.HEART_RATE_SERVICE, Gatt.BATTERY_SERVICE), simulated = true),
        Advertisement("sim-env", "EnviroSense ES2 (sim)", -64, listOf(Gatt.ENVIRONMENTAL_SENSING_SERVICE, Gatt.BATTERY_SERVICE), simulated = true),
        Advertisement("sim-fridge", "Cold Chain Tag (sim)", -81, listOf(Gatt.ENVIRONMENTAL_SENSING_SERVICE), simulated = true),
    )

    override fun scan(): Flow<Advertisement> = flow {
        for (d in devices) {
            delay(250)
            emit(d)
        }
        while (true) {
            delay(1500)
            devices.forEach { emit(it.copy(rssi = it.rssi + random.nextInt(-3, 4))) }
        }
    }

    override suspend fun connect(device: Advertisement): BleConnection {
        delay(400)
        return SimConnection(device)
    }

    private inner class SimConnection(override val device: Advertisement) : BleConnection {
        override val state = MutableStateFlow(LinkState.Connected)

        override val readings: Flow<Reading> = flow {
            var t = 0
            var battery = 87.0
            while (true) {
                val now = clock()
                if (Gatt.HEART_RATE_SERVICE in device.services) {
                    // Warm-up, a workout interval and recovery.
                    val effort = 0.5 + 0.5 * sin(2 * PI * t / 90.0)
                    val bpm = (72 + 70 * effort + random.nextDouble(-3.0, 3.0)).toInt()
                    val rr = 60000 / bpm
                    parseHeartRate(encodeHeartRate(bpm, listOf(rr)))?.let {
                        emit(Reading(Metric.HeartRate, it.bpm.toDouble(), now, "RR ${it.rrIntervalsMs.firstOrNull()} ms"))
                    }
                } else {
                    val fridge = device.id == "sim-fridge"
                    val base = if (fridge) 4.0 else 21.5
                    // The cold-chain tag has a door-open excursion every two minutes.
                    val excursion = if (fridge && t % 120 in 60..80) (t % 120 - 60) * 0.35 else 0.0
                    val temp = base + 0.6 * sin(2 * PI * t / 60.0) + excursion + random.nextDouble(-0.08, 0.08)
                    val hum = (if (fridge) 70.0 else 44.0) + 3 * sin(2 * PI * t / 75.0) + random.nextDouble(-0.4, 0.4)
                    parseTemperatureC(encodeSint16(temp))?.let { emit(Reading(Metric.Temperature, it, now)) }
                    parseHumidity(encodeUint16(hum))?.let { emit(Reading(Metric.Humidity, it, now)) }
                }
                if (Gatt.BATTERY_SERVICE in device.services && t % 10 == 0) {
                    battery -= 0.1
                    parseBattery(byteArrayOf(battery.toInt().toByte()))?.let { emit(Reading(Metric.Battery, it.toDouble(), now)) }
                }
                t++
                delay(tickMs)
            }
        }

        override suspend fun disconnect() {
            state.value = LinkState.Disconnected
        }
    }
}
