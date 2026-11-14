package com.neomaverick.senselink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.neomaverick.senselink.ble.AndroidBleClient
import com.neomaverick.senselink.ble.BleClient
import java.io.File

@SuppressLint("StaticFieldLeak")
object AndroidPlatform {
    var context: Context? = null
    fun init(c: Context) {
        context = c
    }
}

actual fun platformBle(): BleClient? {
    val c = AndroidPlatform.context ?: return null
    val manager = c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
    val adapter = manager.adapter ?: return null
    return AndroidBleClient(c.applicationContext, adapter)
}

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun shareCsv(fileName: String, csv: String) {
    val c = AndroidPlatform.context ?: return
    val dir = File(c.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, fileName).apply { writeText(csv) }
    val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    c.startActivity(Intent.createChooser(send, "Export readings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

actual val platformName: String = "Android"
