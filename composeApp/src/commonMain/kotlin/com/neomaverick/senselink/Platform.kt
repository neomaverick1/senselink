package com.neomaverick.senselink

import com.neomaverick.senselink.ble.BleClient

/** Real Bluetooth for this platform, or null when it isn't available (e.g. in tests). */
expect fun platformBle(): BleClient?

expect fun nowMillis(): Long

/** Opens the system share sheet with a CSV file. */
expect fun shareCsv(fileName: String, csv: String)

expect val platformName: String
