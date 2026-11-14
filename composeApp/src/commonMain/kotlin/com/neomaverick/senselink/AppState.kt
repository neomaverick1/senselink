package com.neomaverick.senselink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neomaverick.senselink.ble.Advertisement
import com.neomaverick.senselink.ble.BleClient
import com.neomaverick.senselink.ble.BleConnection
import com.neomaverick.senselink.ble.LinkState
import com.neomaverick.senselink.ble.Metric
import com.neomaverick.senselink.ble.Reading
import com.neomaverick.senselink.ble.SimulatedBleClient
import com.neomaverick.senselink.logic.AlertEdge
import com.neomaverick.senselink.logic.AlertEngine
import com.neomaverick.senselink.logic.defaultRules
import com.neomaverick.senselink.logic.Series
import com.neomaverick.senselink.logic.toCsv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class AlertEvent(val metric: Metric, val edge: AlertEdge, val value: Double, val atMillis: Long)

/** All screen state; survives navigation, observed by Compose. */
class AppState(
    private val scope: CoroutineScope,
    val real: BleClient? = platformBle(),
    val simulator: SimulatedBleClient = SimulatedBleClient(::nowMillis),
) {
    var useSimulator by mutableStateOf(real == null)
    var scanning by mutableStateOf(false)
    val found = mutableStateMapOf<String, Advertisement>()
    var connection by mutableStateOf<BleConnection?>(null)
    var link by mutableStateOf(LinkState.Disconnected)
    var fahrenheit by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val series = mutableStateMapOf<Metric, Series>()
    var tick by mutableStateOf(0L) // bumps on every reading so charts redraw
    val alerts = mutableStateListOf<AlertEvent>()
    private val log = mutableListOf<Reading>()

    val alertEngine = AlertEngine(emptyList())
    var lastReadingAt by mutableStateOf(0L)

    private var scanJob: Job? = null
    private var readJob: Job? = null
    private var stateJob: Job? = null

    val client: BleClient get() = if (useSimulator || real == null) simulator else real

    fun startScan() {
        stopScan()
        found.clear()
        error = null
        scanning = true
        scanJob = scope.launch {
            try {
                client.scan().collect { found[it.id] = it }
            } catch (t: Throwable) {
                error = t.message ?: "Scan failed"
            } finally {
                scanning = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanning = false
    }

    fun connect(device: Advertisement) {
        stopScan()
        disconnect()
        series.clear()
        alerts.clear()
        log.clear()
        alertEngine.rules = defaultRules(device)
        link = LinkState.Connecting
        scope.launch {
            try {
                val c = client.connect(device)
                connection = c
                stateJob = scope.launch { c.state.collect { link = it } }
                readJob = scope.launch {
                    c.readings.collect { r ->
                        series.getOrPut(r.metric) { Series() }.add(r)
                        log += r
                        lastReadingAt = r.atMillis
                        alertEngine.update(r)?.let { alerts.add(0, AlertEvent(r.metric, it, r.value, r.atMillis)) }
                        tick++
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: "Could not connect"
                link = LinkState.Disconnected
            }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        stateJob?.cancel()
        val c = connection
        connection = null
        link = LinkState.Disconnected
        if (c != null) scope.launch { c.disconnect() }
    }

    fun readingCount() = log.size

    fun exportCsv() {
        val c = connection ?: return
        shareCsv("senselink-${c.device.name.replace(' ', '-').lowercase()}.csv", toCsv(c.device.name, log))
    }
}
