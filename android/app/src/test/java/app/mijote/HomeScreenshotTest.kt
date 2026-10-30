package app.mijote

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.mijote.ui.HomeScreen
import app.mijote.ui.MijoteTheme
import org.junit.Rule
import org.junit.Test

// Renders the home screen on the JVM (no emulator needed) to review the theme.
class HomeScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = true)

    @Test
    fun home() = paparazzi.snapshot { MijoteTheme { HomeScreen() } }
}
