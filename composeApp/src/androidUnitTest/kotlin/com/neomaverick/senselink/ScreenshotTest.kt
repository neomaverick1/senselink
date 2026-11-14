package com.neomaverick.senselink

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.neomaverick.senselink.ble.SimulatedBleClient
import com.neomaverick.senselink.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Renders the real screens with the simulator and saves README screenshots (docs/screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var clock = 1_700_000_000_000L

    private fun state(): AppState {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return AppState(scope, real = null, simulator = SimulatedBleClient(clock = { clock.also { clock += 1000 } }, tickMs = 1000))
    }

    /** Advances virtual time: the simulator ticks once per second of looper time. */
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(250)
            left -= 250
        }
        compose.waitForIdle()
    }

    @Test fun scan() {
        val s = state()
        compose.setContent { App(s) }
        advance(1500)
        compose.onRoot().captureRoboImage("../docs/screenshots/1_scan.png")
    }

    @Test fun heartRate() {
        val s = state()
        compose.setContent { App(s) }
        advance(800)
        s.connect(s.simulator.devices[0])
        advance(70_000)
        compose.onRoot().captureRoboImage("../docs/screenshots/2_heart_rate.png")
    }

    @Test fun environment() {
        val s = state()
        compose.setContent { App(s) }
        advance(800)
        s.connect(s.simulator.devices[1])
        advance(50_000)
        compose.onRoot().captureRoboImage("../docs/screenshots/3_environment.png")
    }

    @Test fun coldChainAlert() {
        val s = state()
        compose.setContent { App(s) }
        advance(800)
        s.connect(s.simulator.devices[2])
        advance(74_000)
        compose.onRoot().captureRoboImage("../docs/screenshots/4_cold_chain_alert.png")
    }
}
