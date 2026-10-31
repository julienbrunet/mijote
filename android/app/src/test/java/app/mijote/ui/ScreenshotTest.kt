package app.mijote.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.mijote.data.User
import org.junit.Rule
import org.junit.Test

// Renders the screens on the JVM (no emulator): ./gradlew :app:recordPaparazziDebug
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = true)

    @Test
    fun server() = paparazzi.snapshot { MijoteTheme { ServerScreen(busy = false, error = null) {} } }

    @Test
    fun loginWithError() = paparazzi.snapshot {
        MijoteTheme { LoginScreen("https://mesrepas.example.fr", false, "Identifiant ou mot de passe incorrect.", { _, _ -> }, {}) }
    }

    @Test
    fun home() = paparazzi.snapshot {
        MijoteTheme { HomeScreen(User("u1", "julien", "Julien", "admin")) {} }
    }
}
