package app.mijote.prototype

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.mijote.prototype.ui.HomeScreen
import app.mijote.prototype.ui.MijoteTheme
import org.junit.Rule
import org.junit.Test

// Renders the home screen on the JVM (no emulator needed) to review the theme.
class HomeScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = true)

    @Test
    fun home() = paparazzi.snapshot { MijoteTheme { HomeScreen() } }
}
