package app.mijote.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.mijote.R
import app.mijote.data.GenerationInfo
import app.mijote.data.User
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

// Renders the screens on the JVM (no emulator): ./gradlew :app:recordPaparazziDebug
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = true)

    @Test
    fun server() = paparazzi.snapshot { MijoteTheme { ServerScreen(busy = false, error = null, onSubmit = {}, onInvite = {}) } }

    @Test
    fun loginWithError() = paparazzi.snapshot {
        MijoteTheme { LoginScreen("https://mesrepas.example.fr", false, "Identifiant ou mot de passe incorrect.", { _, _ -> }, {}, {}) }
    }

    @Test
    fun inviteFromLink() = paparazzi.snapshot {
        MijoteTheme {
            InviteScreen("https://mesrepas.example.fr", "abcdefghijklmnop", false,
                "Cette invitation est invalide, déjà utilisée ou expirée : demandez-en une nouvelle.",
                { _, _, _, _, _, _ -> }, {})
        }
    }

    @Test
    fun inviteByHand() = paparazzi.snapshot {
        MijoteTheme { InviteScreen(null, null, false, null, { _, _, _, _, _, _ -> }, {}) }
    }

    @Test
    fun home() = paparazzi.snapshot {
        MijoteTheme { HomeScreen(User("u1", "julien", "Julien", "admin"), onLogout = {}) }
    }

    @Test
    fun homeWithMenu() = paparazzi.snapshot {
        MijoteTheme { HomeScreen(User("u1", "julien", "Julien", "admin"), onLogout = {}, period = "2 → 8 novembre · 11 repas", days = sampleDays) }
    }

    // Launcher icon under the usual launcher masks, plus the themed (monochrome) variant.
    @Test
    fun launcherIcon() = paparazzi.snapshot {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF3A3A3C)).padding(24.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
        ) {
            listOf(androidx.compose.foundation.shape.CircleShape, androidx.compose.foundation.shape.RoundedCornerShape(28.dp)).forEach { shape ->
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.size(108.dp).clip(shape).background(Sunny.Butter),
                ) { androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null) }
            }
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.size(108.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xFF2F3A2F)),
            ) {
                androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_monochrome), null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFFCFE6C4)))
            }
        }
    }

    @Test
    fun generate() = paparazzi.snapshot {
        MijoteTheme {
            GenerateScreen(GenerationInfo(24, 10, 7, listOf("dinner", "dinner", "dinner", "dinner", "dinner", "lunch_dinner", "lunch_dinner")),
                LocalDate.of(2026, 10, 31), people = 4, busy = false, error = null, onGenerate = { _, _, _ -> })
        }
    }

    @Test
    fun generateNotEnoughRecipes() = paparazzi.snapshot {
        MijoteTheme {
            GenerateScreen(GenerationInfo(4, 10, 7, List(7) { "lunch_dinner" }), LocalDate.of(2026, 10, 31), people = 1,
                busy = false, error = null, onGenerate = { _, _, _ -> })
        }
    }

    private val sampleDays = listOf(
        DayUi("Aujourd'hui", "lun. 2 nov.", listOf(
            MealUi("Midi", "Poulet au pesto et tomates rôties", DishType.MEAT, 25, "🍗", locked = true),
            MealUi("Soir", "Velouté de potimarron", DishType.VEGETARIAN, 35, "🥣", locked = false),
        )),
        DayUi("Demain", "mar. 3 nov.", listOf(
            MealUi("Soir", "Saumon, riz et brocolis", DishType.FISH, 20, "🐟", locked = false),
        )),
        DayUi("Mercredi", "4 nov.", listOf(
            MealUi("Midi", "Gratin de pâtes aux légumes", DishType.VEGETARIAN, 40, "🧀", locked = false),
        )),
    )
}
