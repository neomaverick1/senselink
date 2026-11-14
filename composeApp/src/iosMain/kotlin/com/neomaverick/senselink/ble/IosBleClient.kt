package com.neomaverick.senselink.ble

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.receiveAsFlow
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerStatePoweredOn
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * Real Bluetooth LE on iPhone through CoreBluetooth: scan for the supported services, connect,
 * discover characteristics and subscribe to notifications.
 */
class IosBleClient : BleClient {
    override val name = "Bluetooth LE"
    override val isAvailable get() = central.state == CBCentralManagerStatePoweredOn

    private val wanted = listOf(Gatt.HEART_RATE_SERVICE, Gatt.ENVIRONMENTAL_SENSING_SERVICE).map { CBUUID.UUIDWithString(Gatt.short(it)) }
    private val peripherals = mutableMapOf<String, CBPeripheral>()
    private var onDiscover: ((Advertisement) -> Unit)? = null
    private var onConnected: ((CBPeripheral) -> Unit)? = null
    private var onDisconnected: ((CBPeripheral) -> Unit)? = null
    private var pendingScan = false

    private val delegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBCentralManagerStatePoweredOn && pendingScan) central.scanForPeripheralsWithServices(wanted, null)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val id = didDiscoverPeripheral.identifier.UUIDString
            peripherals[id] = didDiscoverPeripheral
            @Suppress("UNCHECKED_CAST")
            val uuids = (advertisementData["kCBAdvDataServiceUUIDs"] as? List<CBUUID>).orEmpty()
            onDiscover?.invoke(
                Advertisement(
                    id = id,
                    name = didDiscoverPeripheral.name ?: (advertisementData["kCBAdvDataLocalName"] as? String) ?: "Unknown",
                    rssi = RSSI.intValue,
                    services = uuids.map { "0000${it.UUIDString.lowercase()}-0000-1000-8000-00805f9b34fb" },
                ),
            )
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            onConnected?.invoke(didConnectPeripheral)
        }

        @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            onDisconnected?.invoke(didDisconnectPeripheral)
        }
    }

    private val central = CBCentralManager(delegate, null)

    override fun scan(): Flow<Advertisement> = callbackFlow {
        onDiscover = { trySend(it) }
        pendingScan = true
        if (central.state == CBCentralManagerStatePoweredOn) central.scanForPeripheralsWithServices(wanted, null)
        awaitClose {
            pendingScan = false
            central.stopScan()
            onDiscover = null
        }
    }

    override suspend fun connect(device: Advertisement): BleConnection {
        val p = peripherals[device.id] ?: error("Device is no longer nearby")
        return IosConnection(device, p)
    }

    private inner class IosConnection(override val device: Advertisement, private val peripheral: CBPeripheral) : BleConnection {
        override val state = MutableStateFlow(LinkState.Connecting)
        private val channel = Channel<Reading>(Channel.BUFFERED)
        override val readings: Flow<Reading> = channel.receiveAsFlow()

        private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {
            override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
                peripheral.services?.forEach { peripheral.discoverCharacteristics(null, it as CBService) }
            }

            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
                didDiscoverCharacteristicsForService.characteristics?.forEach {
                    val c = it as CBCharacteristic
                    when (c.UUID.UUIDString.uppercase()) {
                        "2A37", "2A6E", "2A6F" -> peripheral.setNotifyValue(true, c)
                        "2A19" -> peripheral.readValueForCharacteristic(c)
                    }
                }
            }

            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
                val bytes = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
                val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
                val r = when (didUpdateValueForCharacteristic.UUID.UUIDString.uppercase()) {
                    "2A37" -> parseHeartRate(bytes)?.let {
                        Reading(Metric.HeartRate, it.bpm.toDouble(), now, it.rrIntervalsMs.firstOrNull()?.let { rr -> "RR $rr ms" })
                    }
                    "2A6E" -> parseTemperatureC(bytes)?.let { Reading(Metric.Temperature, it, now) }
                    "2A6F" -> parseHumidity(bytes)?.let { Reading(Metric.Humidity, it, now) }
                    "2A19" -> parseBattery(bytes)?.let { Reading(Metric.Battery, it.toDouble(), now) }
                    else -> null
                }
                if (r != null) channel.trySend(r)
            }
        }

        init {
            peripheral.delegate = peripheralDelegate
            onConnected = {
                if (it == peripheral) {
                    state.value = LinkState.Connected
                    peripheral.discoverServices(null)
                }
            }
            onDisconnected = {
                if (it == peripheral && state.value != LinkState.Disconnected) {
                    state.value = LinkState.Reconnecting
                    central.connectPeripheral(peripheral, null) // iOS keeps this pending until the sensor is back
                }
            }
            central.connectPeripheral(peripheral, null)
        }

        override suspend fun disconnect() {
            state.value = LinkState.Disconnected
            central.cancelPeripheralConnection(peripheral)
            channel.close()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
