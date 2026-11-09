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
    fun homeLoading() = paparazzi.snapshot {
        MijoteTheme { HomeScreen(User("u1", "julien", "Julien", "admin"), onLogout = {}, loading = true, menuLoaded = false) }
    }

    @Test
    fun homeConnectionError() = paparazzi.snapshot {
        MijoteTheme { HomeScreen(User("u1", "julien", "Julien", "admin"), onLogout = {}, error = "Connection error", menuLoaded = false) }
    }

    @Test
    fun homeWithMenu() = paparazzi.snapshot {
        MijoteTheme { HomeScreen(User("u1", "julien", "Julien", "admin"), onLogout = {}, period = "2 → 8 novembre · 11 repas", days = sampleDays) }
    }

    @Test
    fun homeLongRecipeTitle() = paparazzi.snapshot {
        val days = sampleDays.mapIndexed { index, day ->
            if (index == 2) day.copy(meals = day.meals.mapIndexed { mealIndex, meal ->
                if (mealIndex == 0) meal.copy(title = "Gratin de pâtes aux courgettes, tomates rôties et mozzarella fondante") else meal
            }) else day
        }
        MijoteTheme { HomeScreen(User("u1", "julien", "Julien", "admin"), onLogout = {}, period = "2 → 8 novembre · 11 repas", days = days) }
    }

    @Test
    fun homeCompactDays() = paparazzi.snapshot {
        MijoteTheme { HomeScreen(User("u1", "julien", "Julien", "admin"), onLogout = {}, period = "2 → 8 novembre · 11 repas", days = sampleDays.drop(1)) }
    }

    @Test
    fun homeNarrowLongRecipeTitle() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(
            screenWidth = 320, screenHeight = 800, xdpi = 160, ydpi = 160,
            density = com.android.resources.Density.MEDIUM,
        ))
        homeLongRecipeTitle()
    }

    @Test
    fun homeWithMenuReadonly() = paparazzi.snapshot {
        MijoteTheme { HomeScreen(User("u2", "enfant", "Enfant", "readonly"), onLogout = {}, period = "2 → 8 novembre · 11 repas", days = sampleDays) }
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
    fun homeWithNotifications() = paparazzi.snapshot {
        MijoteTheme { HomeScreen(User("u1", "parent", "Parent", "member"), onLogout = {}, days = sampleDays,
            unreadNotifications = 2, onNotifications = {}) }
    }

    @Test
    fun homeWithReadNotifications() = paparazzi.snapshot {
        MijoteTheme { HomeScreen(User("u1", "parent", "Parent", "member"), onLogout = {}, days = sampleDays,
            unreadNotifications = 0, hasNotifications = true, onNotifications = {}) }
    }

    @Test
    fun recipeReadonlyWish() = paparazzi.snapshot {
        MijoteTheme {
            RecipeScreen(app.mijote.RecipeState("r1", "Gratin de pâtes aux légumes",
                app.mijote.data.RecipeDetail("r1", "Gratin de pâtes aux légumes", null, "vegetarian", 15, 25, 40, null,
                    listOf(app.mijote.data.IngredientLine(100.0,"g","pâtes",null)), listOf("Cuire les pâtes, puis enfourner.")),
                wish = app.mijote.data.WishStatus(false,5,1,4,java.time.Instant.parse("2026-11-08T23:00:00Z"))),
                people = 1, onBack = {}, onWish = {})
        }
    }

    @Test
    fun notifications() = paparazzi.snapshot {
        val now = java.time.Instant.parse("2026-11-03T18:00:00Z")
        MijoteTheme {
            NotificationsScreen(app.mijote.NotificationsState(unreadCount = 1, items = listOf(
                app.mijote.data.RecipeNotification("n1","Léa","r1","Gratin de pâtes aux légumes",now,null),
                app.mijote.data.RecipeNotification("n2","Max","r2","Poulet au pesto et tomates rôties",now.minusSeconds(3600),now))),
                onBack = {}, onRefresh = {}, onMore = {}, onOpen = {}, onRead = {}, onDelete = {})
        }
    }

    @Test
    fun preferences() = paparazzi.snapshot {
        MijoteTheme {
            PreferencesScreen(User("u1", "julien", "Julien", "admin", people = 4), "https://mesrepas.example.fr", "0.1.31",
                checkingUpdate = false, updateMessage = "L'application est à jour.",
                onPeople = {}, onCheckUpdate = {}, onChangeServer = {}, onLogout = {}, onBack = {},
                wishSettings = app.mijote.WishSettingsState(limit = 5), notificationCount = 2)
        }
    }

    @Test
    fun preferencesReadonly() = paparazzi.snapshot {
        MijoteTheme {
            PreferencesScreen(User("u2", "enfant", "Enfant", "readonly", people = 1), "https://mesrepas.example.fr", "0.1.31",
                checkingUpdate = false, updateMessage = "L'application est à jour.",
                onPeople = {}, onCheckUpdate = {}, onChangeServer = {}, onLogout = {}, onBack = {})
        }
    }

    @Test
    fun generateWithNavigation() = generationForm()

    @Test
    fun generateWithNavigationScrolled() = generationForm(bottom = true)

    private fun generationForm(bottom: Boolean = false) = paparazzi.snapshot {
        MijoteTheme {
            HomeScreen(User("u1", "julien", "Julien", "admin"), onLogout = {}, tab = Tab.GENERATE, generate = {
                GenerateScreen(GenerationInfo(24, 10, 7, List(7) { "dinner" }), LocalDate.of(2026, 11, 2),
                    people = 4, busy = false, error = null, onGenerate = { _, _, _ -> },
                    scrollState = androidx.compose.foundation.rememberScrollState(initial = if (bottom) Int.MAX_VALUE else 0),
                    slots = listOf(
                        app.mijote.data.MenuSlot("s1", LocalDate.of(2026, 11, 3), "dinner", null, locked = false),
                        app.mijote.data.MenuSlot("s2", LocalDate.of(2026, 11, 4), "dinner", null, locked = true, title = "Pizza")))
            })
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

    @Test
    fun review() = paparazzi.snapshot {
        MijoteTheme {
            ReviewScreen("2 → 4 novembre", sampleDays.mapIndexed { i, d -> d.copy(missing = if (i == 1) listOf("lunch") else emptyList()) },
                working = null, error = null, onOpenMeal = {}, onSwapMeal = {}, onRemoveMeal = {}, onAddMeal = { _, _ -> }, onValidate = {})
        }
    }

    @Test
    fun reviewWithMoveHandles() = paparazzi.snapshot {
        MijoteTheme {
            ReviewScreen("2 → 4 novembre", sampleDays.mapIndexed { dayIndex, day ->
                day.copy(meals = day.meals.mapIndexed { mealIndex, meal -> meal.copy(id = "s$dayIndex-$mealIndex") })
            }, working = null, error = null, onOpenMeal = {}, onSwapMeal = {}, onRemoveMeal = {},
                onAddMeal = { _, _ -> }, onValidate = {})
        }
    }

    @Test
    fun reviewWithPeople() = paparazzi.snapshot {
        MijoteTheme {
            ReviewScreen("2 → 3 novembre", listOf(
                DayUi("Aujourd'hui", "lun. 2 nov.", listOf(
                    MealUi("Midi", "Poulet au pesto et tomates rôties", DishType.MEAT, 25, "🍗", false, id = "s1", recipeId = "r1", people = 4),
                    MealUi("Soir", "Velouté de potimarron", DishType.VEGETARIAN, 35, "🥣", false, id = "s2", recipeId = "r2", people = 6))),
            ), working = null, error = null, onOpenMeal = {}, onSwapMeal = {}, onRemoveMeal = {}, onAddMeal = { _, _ -> }, onValidate = {})
        }
    }

    @Test
    fun peopleDialog() = paparazzi.snapshot { MijoteTheme { PeopleDialog("Velouté de potimarron", 6, onDismiss = {}, onConfirm = {}) } }

    @Test
    fun recipe() = paparazzi.snapshot {
        MijoteTheme {
            RecipeScreen(
                app.mijote.RecipeState("r1", "Poulet au pesto et tomates rôties", app.mijote.data.RecipeDetail(
                    "r1", "Poulet au pesto et tomates rôties", null, "meat", 10, 25, 35, "Se réchauffe très bien.",
                    listOf(
                        app.mijote.data.IngredientLine(150.0, "g", "blanc de poulet", null),
                        app.mijote.data.IngredientLine(1.0, "tbsp", "pesto", null),
                        app.mijote.data.IngredientLine(null, "pinch", "sel", null),
                    ),
                    listOf("Préchauffer le four à 200 °C.", "Enfourner 25 minutes."))),
                people = 4, onBack = {})
        }
    }

    @Test
    fun recipeForMember() = paparazzi.snapshot {
        MijoteTheme {
            RecipeScreen(
                app.mijote.RecipeState("r1", "Velouté de potimarron", app.mijote.data.RecipeDetail(
                    "r1", "Velouté de potimarron", null, "vegetarian", 15, 30, 45, null,
                    listOf(app.mijote.data.IngredientLine(300.0, "g", "potimarron", null)), listOf("Cuire 30 minutes."), favorite = true)),
                people = 2, onBack = {}, canWrite = true, canArchive = true)
        }
    }

    @Test
    fun recipesTab() = paparazzi.snapshot {
        MijoteTheme {
            RecipesTab(app.mijote.RecipesState("pâtes", listOf(
                app.mijote.data.RecipeSummary("r1", "Gratin de pâtes aux légumes", "vegetarian", 40, null, listOf("four", "hiver")),
                app.mijote.data.RecipeSummary("r2", "Pâtes au saumon", "fish", 20, null, emptyList()),
            )), canWrite = true, onQuery = {}, onOpen = {}, onPlan = {})
        }
    }

    @Test
    fun recipesTabFiltered() = paparazzi.snapshot {
        MijoteTheme {
            RecipesTab(app.mijote.RecipesState("", listOf(
                app.mijote.data.RecipeSummary("r1", "Gratin de pâtes aux légumes", "vegetarian", 25, null, listOf("four", "hiver"), favorite = true),
                app.mijote.data.RecipeSummary("r2", "Pâtes au saumon", "fish", 20, null, emptyList()),
                app.mijote.data.RecipeSummary("r3", "Velouté de potimarron", "vegetarian", 30, null, listOf("soupe")),
            ), filters = app.mijote.data.RecipeFilters(favorites = true, dishTypes = setOf("vegetarian"))),
                canWrite = true, onQuery = {}, onOpen = {}, onPlan = {})
        }
    }

    @Test
    fun homeRecipesTab() = paparazzi.snapshot {
        MijoteTheme {
            HomeScreen(User("u1", "julien", "Julien", "admin"), onLogout = {}, tab = Tab.RECIPES, onAddRecipe = {}, onFilterRecipes = {}, filterCount = 2, recipes = {
                RecipesTab(app.mijote.RecipesState("", listOf(
                    app.mijote.data.RecipeSummary("r1", "Bowl au chèvre pané & crudités", "vegetarian", 15, null, listOf("Bowl")),
                    app.mijote.data.RecipeSummary("r2", "Bowl surimi & tzatziki", "fish", 16, null, listOf("Bowl")),
                )), canWrite = true, onQuery = {}, onOpen = {}, onPlan = {})
            })
        }
    }

    @Test
    fun recipeFiltersSheet() = paparazzi.snapshot {
        MijoteTheme {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(Sunny.Card)) {
                RecipeFiltersSheet(app.mijote.data.RecipeFilters(dishTypes = setOf("vegetarian"), durations = setOf("quick")),
                    listOf("four", "hiver", "soupe"), listOf(
                        app.mijote.data.RecipeSummary("r1", "Gratin", "vegetarian", 25, null, listOf("four")),
                        app.mijote.data.RecipeSummary("r2", "Saumon", "fish", 20, null, emptyList())),
                    onChange = {}, onDone = {})
            }
        }
    }

    @Test
    fun recipeEditor() = paparazzi.snapshot {
        MijoteTheme {
            RecipeEditorScreen(app.mijote.EditorState(null, app.mijote.data.RecipeDraft(title = "Quiche lorraine", dishType = "meat",
                prepMinutes = "15", ingredients = "100 g de lardons\n2 œufs\n20 cl de crème")), onChange = {}, onSave = {}, onBack = {})
        }
    }

    @Test
    fun planRecipe() = paparazzi.snapshot {
        MijoteTheme {
            PlanMealDialog("Gratin de pâtes aux légumes", LocalDate.of(2026, 11, 2), listOf(app.mijote.data.MenuSlot("s1", LocalDate.of(2026, 11, 2), "dinner",
                app.mijote.data.MenuRecipe("r1", "Soupe", "vegetarian", 20), false)), onDismiss = {}, onConfirm = { _, _, _ -> })
        }
    }

    @Test
    fun manualMealDialog() = paparazzi.snapshot {
        MijoteTheme { MealTitleDialog("", onDismiss = {}, onConfirm = {}) }
    }

    @Test
    fun invitations() = paparazzi.snapshot {
        MijoteTheme {
            InvitationsScreen(app.mijote.AdminState(invitations = app.mijote.data.Invitations(listOf(
                app.mijote.data.Invitation("i1", "member", "pending", "2026-11-09T10:00:00Z", null)), listOf(1, 7, 30), 7)),
                onCreate = { _, _ -> }, onRevoke = {}, onDismissCreated = {}, onBack = {})
        }
    }

    @Test
    fun accounts() = paparazzi.snapshot {
        MijoteTheme {
            AccountsScreen(app.mijote.AdminState(accounts = listOf(
                app.mijote.data.Account("u1", "julien", "Julien", "admin", false),
                app.mijote.data.Account("u2", "lea", "Léa", "readonly", true))),
                onRole = { _, _ -> }, onDisabled = { _, _ -> }, onPassword = { _, _ -> }, onBack = {})
        }
    }

    // Candidate icons for the "Idées de repas" tab, on the navigation pill colour.
    @Test
    fun mealIdeaIcons() = paparazzi.snapshot {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.background(Sunny.PeachDeep).padding(24.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(32.dp),
        ) {
            listOf(R.drawable.ic_meal_idea, R.drawable.ic_lightbulb).forEach {
                androidx.compose.foundation.Image(painterResource(it), null, androidx.compose.ui.Modifier.size(72.dp))
            }
        }
    }

    private val sampleDays = listOf(
        DayUi("Aujourd'hui", "lun. 2 nov.", listOf(
            MealUi("Midi", "Poulet au pesto et tomates rôties", DishType.MEAT, 25, "🍗", locked = true),
            MealUi("Soir", "Velouté de potimarron", DishType.VEGETARIAN, 35, "🥣", locked = false),
        ), day = LocalDate.of(2026, 11, 2), isToday = true),
        DayUi("Demain", "mar. 3 nov.", listOf(
            MealUi("Soir", "Saumon, riz et brocolis", DishType.FISH, 20, "🐟", locked = false),
        ), day = LocalDate.of(2026, 11, 3)),
        DayUi("Mercredi", "4 nov.", listOf(
            MealUi("Midi", "Gratin de pâtes aux légumes", DishType.VEGETARIAN, 40, "🧀", locked = false),
            MealUi("Soir", "Commander une pizza", null, null, "📝", locked = true, manual = true),
        ), day = LocalDate.of(2026, 11, 4)),
    ).mapIndexed { dayIndex, day -> day.copy(meals = day.meals.mapIndexed { mealIndex, meal ->
        meal.copy(id = "sample-$dayIndex-$mealIndex")
    }) }
}
