package com.neomaverick.senselink.ble

import kotlinx.coroutines.flow.Flow

enum class Metric(val label: String, val unit: String) {
    HeartRate("Heart rate", "bpm"),
    Temperature("Temperature", "°C"),
    Humidity("Humidity", "%"),
    Battery("Battery", "%"),
}

data class Advertisement(
    val id: String,
    val name: String,
    val rssi: Int,
    val services: List<String>,
    val simulated: Boolean = false,
) {
    val kind: String
        get() = when {
            Gatt.HEART_RATE_SERVICE in services -> "Heart rate monitor"
            Gatt.ENVIRONMENTAL_SENSING_SERVICE in services -> "Environment sensor"
            else -> "BLE device"
        }

    /** 0..4 signal bars from RSSI (dBm). */
    val bars: Int get() = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
}

data class Reading(val metric: Metric, val value: Double, val atMillis: Long, val extra: String? = null)

enum class LinkState { Connecting, Connected, Reconnecting, Disconnected }

interface BleConnection {
    val device: Advertisement
    val state: Flow<LinkState>
    val readings: Flow<Reading>
    suspend fun disconnect()
}

/** Platform BLE (Android BluetoothLeScanner/GATT, iOS CoreBluetooth) or the built-in simulator. */
interface BleClient {
    val name: String
    val isAvailable: Boolean
    fun scan(): Flow<Advertisement>
    suspend fun connect(device: Advertisement): BleConnection
}
