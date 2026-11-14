package com.neomaverick.senselink.ble

/**
 * Bluetooth SIG GATT services and characteristics SenseLink understands, with byte-level parsers
 * for the standard formats (little-endian, as the spec requires).
 */
object Gatt {
    const val HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
    const val HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"
    const val ENVIRONMENTAL_SENSING_SERVICE = "0000181a-0000-1000-8000-00805f9b34fb"
    const val TEMPERATURE = "00002a6e-0000-1000-8000-00805f9b34fb"
    const val HUMIDITY = "00002a6f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"
    const val CLIENT_CONFIG_DESCRIPTOR = "00002902-0000-1000-8000-00805f9b34fb"

    fun short(uuid: String): String = uuid.substring(4, 8).uppercase()
}

data class HeartRate(
    val bpm: Int,
    val sensorContact: Boolean?,
    val energyKj: Int?,
    val rrIntervalsMs: List<Int>,
)

private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()

/** Heart Rate Measurement (0x2A37): flags byte, then UINT8 or UINT16 bpm, optional energy, RR intervals (1/1024 s). */
fun parseHeartRate(data: ByteArray): HeartRate? {
    if (data.size < 2) return null
    val flags = u8(data, 0)
    val wide = flags and 0x01 != 0
    var i = 1
    if (wide && data.size < 3) return null
    val bpm = if (wide) u16(data, i).also { i += 2 } else u8(data, i).also { i += 1 }
    val contact = when ((flags shr 1) and 0x03) {
        2 -> false
        3 -> true
        else -> null // not supported by this sensor
    }
    var energy: Int? = null
    if (flags and 0x08 != 0) {
        if (data.size < i + 2) return null
        energy = u16(data, i)
        i += 2
    }
    val rr = mutableListOf<Int>()
    if (flags and 0x10 != 0) {
        while (i + 1 < data.size) {
            rr += (u16(data, i) * 1000 + 512) / 1024
            i += 2
        }
    }
    return HeartRate(bpm, contact, energy, rr)
}

/** Temperature (0x2A6E): SINT16, 0.01 °C. 0x8000 means "unknown". */
fun parseTemperatureC(data: ByteArray): Double? {
    if (data.size < 2) return null
    val raw = s16(data, 0)
    if (raw == Short.MIN_VALUE.toInt()) return null
    return raw / 100.0
}

/** Humidity (0x2A6F): UINT16, 0.01 %. 0xFFFF means "unknown". */
fun parseHumidity(data: ByteArray): Double? {
    if (data.size < 2) return null
    val raw = u16(data, 0)
    if (raw == 0xFFFF) return null
    return raw / 100.0
}

/** Battery Level (0x2A19): UINT8 percent. */
fun parseBattery(data: ByteArray): Int? = if (data.isEmpty()) null else u8(data, 0).coerceAtMost(100)

// Encoders (used by the simulator and tests, mirroring what real sensors send).
fun encodeHeartRate(bpm: Int, rrMs: List<Int> = emptyList(), contact: Boolean = true): ByteArray {
    val wide = bpm > 255
    var flags = if (wide) 0x01 else 0
    flags = flags or (if (contact) 0x06 else 0x04)
    if (rrMs.isNotEmpty()) flags = flags or 0x10
    val out = mutableListOf(flags.toByte())
    if (wide) {
        out += (bpm and 0xFF).toByte(); out += (bpm shr 8).toByte()
    } else out += bpm.toByte()
    rrMs.forEach {
        val v = (it * 1024 + 500) / 1000
        out += (v and 0xFF).toByte(); out += (v shr 8).toByte()
    }
    return out.toByteArray()
}

fun encodeSint16(value: Double): ByteArray {
    val v = kotlin.math.round(value * 100).toInt()
    return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}

fun encodeUint16(value: Double): ByteArray {
    val v = kotlin.math.round(value * 100).toInt().coerceIn(0, 0xFFFE)
    return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}
