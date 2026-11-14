package com.neomaverick.senselink

import com.neomaverick.senselink.ble.BleClient
import com.neomaverick.senselink.ble.IosBleClient
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

actual fun platformBle(): BleClient? = IosBleClient()

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun shareCsv(fileName: String, csv: String) {
    val path = NSTemporaryDirectory() + fileName
    NSString.create(string = csv).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    val url = NSURL.fileURLWithPath(path)
    val vc = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
    UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(vc, animated = true, completion = null)
}

actual val platformName: String = "iOS"
