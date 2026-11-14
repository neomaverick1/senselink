package com.neomaverick.senselink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID

/**
 * Real Bluetooth LE on Android: scans for devices advertising the supported services, connects
 * over GATT, subscribes to notifications (writing the CCCD) and parses each value.
 * Android allows one outstanding GATT operation at a time, so writes go through a small queue.
 */
@SuppressLint("MissingPermission")
class AndroidBleClient(private val context: Context, private val adapter: BluetoothAdapter) : BleClient {
    override val name = "Bluetooth LE"
    override val isAvailable get() = adapter.isEnabled

    private val wanted = listOf(Gatt.HEART_RATE_SERVICE, Gatt.ENVIRONMENTAL_SENSING_SERVICE)

    override fun scan(): Flow<Advertisement> = callbackFlow {
        val scanner = adapter.bluetoothLeScanner ?: run {
            close(IllegalStateException("Turn Bluetooth on to scan"))
            return@callbackFlow
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val uuids = r.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
                trySend(
                    Advertisement(
                        id = r.device.address,
                        name = r.scanRecord?.deviceName ?: r.device.name ?: "Unknown ${r.device.address.takeLast(5)}",
                        rssi = r.rssi,
                        services = uuids,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Scan failed ($errorCode)"))
            }
        }
        val filters = wanted.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(it))).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
        awaitClose { scanner.stopScan(cb) }
    }

    override suspend fun connect(device: Advertisement): BleConnection = AndroidConnection(device, adapter.getRemoteDevice(device.id))

    private inner class AndroidConnection(override val device: Advertisement, remote: BluetoothDevice) : BleConnection {
        override val state = MutableStateFlow(LinkState.Connecting)
        private val channel = Channel<Reading>(Channel.BUFFERED)
        override val readings: Flow<Reading> = channel.receiveAsFlow()
        private val pending = ArrayDeque<() -> Unit>()
        private var busy = false

        private fun enqueue(op: () -> Unit) {
            pending.addLast(op)
            if (!busy) next()
        }

        private fun next() {
            val op = pending.removeFirstOrNull()
            busy = op != null
            op?.invoke()
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        state.value = LinkState.Connected
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        // autoConnect=true lets Android reconnect when the sensor comes back in range.
                        state.value = if (status == BluetoothGatt.GATT_SUCCESS) LinkState.Disconnected else LinkState.Reconnecting
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val notify = listOf(
                    Gatt.HEART_RATE_SERVICE to Gatt.HEART_RATE_MEASUREMENT,
                    Gatt.ENVIRONMENTAL_SENSING_SERVICE to Gatt.TEMPERATURE,
                    Gatt.ENVIRONMENTAL_SENSING_SERVICE to Gatt.HUMIDITY,
                )
                for ((svc, chr) in notify) {
                    val c = g.getService(UUID.fromString(svc))?.getCharacteristic(UUID.fromString(chr)) ?: continue
                    enqueue { subscribe(g, c) }
                }
                g.getService(UUID.fromString(Gatt.BATTERY_SERVICE))?.getCharacteristic(UUID.fromString(Gatt.BATTERY_LEVEL))?.let {
                    enqueue { g.readCharacteristic(it) }
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = next()

            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                handle(c.uuid.toString(), value)
                next()
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
                handle(c.uuid.toString(), value)

            @Deprecated("Android 12 and older")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                handle(c.uuid.toString(), c.value ?: return)
            }
        }

        private val gatt: BluetoothGatt = remote.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)

        private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            g.setCharacteristicNotification(c, true)
            val cccd = c.getDescriptor(UUID.fromString(Gatt.CLIENT_CONFIG_DESCRIPTOR)) ?: return next()
            val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        private fun handle(uuid: String, value: ByteArray) {
            val now = System.currentTimeMillis()
            val r = when (uuid) {
                Gatt.HEART_RATE_MEASUREMENT -> parseHeartRate(value)?.let {
                    Reading(Metric.HeartRate, it.bpm.toDouble(), now, it.rrIntervalsMs.firstOrNull()?.let { rr -> "RR $rr ms" })
                }
                Gatt.TEMPERATURE -> parseTemperatureC(value)?.let { Reading(Metric.Temperature, it, now) }
                Gatt.HUMIDITY -> parseHumidity(value)?.let { Reading(Metric.Humidity, it, now) }
                Gatt.BATTERY_LEVEL -> parseBattery(value)?.let { Reading(Metric.Battery, it.toDouble(), now) }
                else -> null
            }
            if (r != null) channel.trySend(r)
        }

        override suspend fun disconnect() {
            gatt.disconnect()
            gatt.close()
            state.value = LinkState.Disconnected
            channel.close()
        }
    }
}
