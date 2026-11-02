package app.mijote

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import java.time.LocalDate
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mijote.ui.GenerateScreen
import app.mijote.ui.HomeScreen
import app.mijote.ui.InviteScreen
import app.mijote.ui.LoginScreen
import app.mijote.ui.MijoteTheme
import app.mijote.ui.PreferencesScreen
import androidx.activity.compose.BackHandler
import app.mijote.ui.ServerScreen
import app.mijote.ui.UpdateDialog
import app.mijote.ui.LocalImageLoader
import app.mijote.ui.RecipeScreen
import app.mijote.ui.ReviewScreen
import app.mijote.ui.menuDays
import app.mijote.ui.menuPeriod
import app.mijote.ui.reviewDays
import app.mijote.ui.periodLabel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.mijote.ui.AccountsScreen
import app.mijote.ui.InvitationsScreen
import app.mijote.ui.PlanMealDialog
import app.mijote.ui.RecipeEditorScreen
import app.mijote.ui.RecipesTab
import app.mijote.ui.Tab

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // Back in the foreground (not the first start): fetch what other members changed meanwhile.
    override fun onRestart() {
        super.onRestart()
        vm.onAppForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { vm.openInvitationLink(it.toString()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light-only app: dark status and navigation bar icons whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // A link that launched the app (not a re-creation after rotation).
        if (savedInstanceState == null) intent?.data?.let { vm.openInvitationLink(it.toString()) }
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val update by vm.update.collectAsStateWithLifecycle()
            val menu by vm.menu.collectAsStateWithLifecycle()
            val check by vm.updateCheck.collectAsStateWithLifecycle()
            val recipe by vm.recipe.collectAsStateWithLifecycle()
            val tab by vm.tab.collectAsStateWithLifecycle()
            val recipes by vm.recipes.collectAsStateWithLifecycle()
            val editor by vm.editor.collectAsStateWithLifecycle()
            val admin by vm.admin.collectAsStateWithLifecycle()
            // What the "plan on a day" dialog is open for: a recipe (id, title) or a manual meal (id null).
            var planning by remember { mutableStateOf<Pair<String?, String>?>(null) }
            MijoteTheme { CompositionLocalProvider(LocalImageLoader provides { path -> vm.image(path) }) {
                UpdateDialog(update, vm.currentVersion, onInstall = vm::installUpdate, onDismiss = vm::dismissUpdate)
                when (val s = state.screen) {
                    Screen.Server -> ServerScreen(state.busy, state.error, onSubmit = vm::submitServer,
                        onInvite = vm::showInvite)
                    is Screen.Login -> LoginScreen(s.serverUrl, state.busy, state.error,
                        onLogin = vm::login, onChangeServer = vm::changeServer, onInvite = vm::showInvite)
                    is Screen.Invite -> InviteScreen(s.serverUrl, s.code, state.busy, state.error,
                        onSubmit = vm::redeemInvitation, onBack = vm::backFromInvite)
                    is Screen.Preferences -> {
                        BackHandler(onBack = vm::closePreferences)
                        PreferencesScreen(s.user, vm.serverUrl, vm.currentVersion, check.checking, check.message ?: state.error,
                            onPeople = vm::setPeople, onCheckUpdate = vm::checkForUpdate, onChangeServer = vm::leaveServer,
                            onLogout = vm::logout, onBack = vm::closePreferences,
                            onInvitations = { vm.openAdmin(AdminSection.INVITATIONS) },
                            onAccounts = { vm.openAdmin(AdminSection.ACCOUNTS) })
                    }
                    is Screen.Admin -> {
                        BackHandler(onBack = vm::closeAdmin)
                        when (s.section) {
                            AdminSection.INVITATIONS -> InvitationsScreen(admin, onCreate = vm::createInvitation,
                                onRevoke = { vm.revokeInvitation(it.id) }, onDismissCreated = vm::dismissCreatedInvitation,
                                onBack = vm::closeAdmin)
                            AdminSection.ACCOUNTS -> AccountsScreen(admin,
                                onRole = { a, role -> vm.updateAccount(a.id, role = role) },
                                onDisabled = { a, off -> vm.updateAccount(a.id, disabled = off) },
                                onPassword = { a, pw -> vm.resetPassword(a.id, pw) },
                                onBack = vm::closeAdmin)
                        }
                    }
                    is Screen.Home -> {
                        val m = menu.menu
                        val review = menu.review
                        val open = { meal: app.mijote.ui.MealUi -> meal.recipeId?.let { vm.openRecipe(it, meal.title) }; Unit }
                        val today = m?.today ?: LocalDate.now()
                        planning?.let { (recipeId, heading) ->
                            PlanMealDialog(heading, today, m?.slots.orEmpty(), manual = recipeId == null,
                                onDismiss = { planning = null },
                                onConfirm = { date, meal, text, replace ->
                                    planning = null
                                    vm.planMeal(date, meal, recipeId, text, replace)
                                })
                        }
                        val edited = editor
                        if (edited != null) BackHandler(onBack = vm::closeEditor)
                        else if (recipe != null) BackHandler(onBack = vm::closeRecipe)
                        else if (review != null) BackHandler(onBack = vm::closeReview)
                        else if (tab != Tab.MENUS) BackHandler { vm.selectTab(Tab.MENUS) }
                        val shown = recipe
                        if (edited != null) {
                            RecipeEditorScreen(edited, onChange = vm::updateDraft, onSave = vm::saveRecipe, onBack = vm::closeEditor)
                        } else if (shown != null) {
                            RecipeScreen(shown, s.user.people, onBack = vm::closeRecipe,
                                canWrite = s.user.canWrite, canArchive = s.user.isAdmin,
                                onPlan = { planning = shown.id to (shown.recipe?.title ?: shown.title) },
                                onEdit = { vm.editRecipe(new = false) }, onArchive = vm::archiveRecipe)
                        } else if (review != null && m != null) {
                            ReviewScreen(
                                period = periodLabel(review.from, review.to),
                                days = reviewDays(m, review.from, review.to),
                                working = menu.working, error = menu.error,
                                onOpenMeal = open,
                                onSwapMeal = { vm.swapMeal(it.id) },
                                onRemoveMeal = { vm.removeMeal(it.id) },
                                onAddMeal = vm::addMeal,
                                onValidate = vm::closeReview,
                                onAddManualMeal = { date, meal, title -> vm.planMeal(date, meal, null, title, replace = false) },
                                onRenameMeal = { meal, title -> vm.renameMeal(meal.id, title) },
                            )
                        } else {
                            HomeScreen(
                                s.user, onLogout = vm::logout,
                                period = m?.let(::menuPeriod), days = m?.let(::menuDays).orEmpty(),
                                generatedCount = 0, onNewWeek = vm::loadMenu, onPreferences = vm::openPreferences,
                                working = menu.working, error = menu.error,
                                onOpenMeal = open,
                                onSwapMeal = { vm.swapMeal(it.id) },
                                onRemoveMeal = { vm.removeMeal(it.id) },
                                onRenameMeal = { meal, title -> vm.renameMeal(meal.id, title) },
                                onAddManualMeal = { planning = null to "" },
                                tab = tab, onTab = vm::selectTab,
                                refreshing = menu.refreshing, onRefresh = { vm.loadMenu(manual = true) },
                                recipes = {
                                    RecipesTab(recipes, canWrite = s.user.canWrite,
                                        onQuery = { vm.searchRecipes(it, debounce = true) },
                                        onOpen = { vm.openRecipe(it.id, it.title) },
                                        onPlan = { planning = it.id to it.title },
                                        onNew = { vm.editRecipe(new = true) },
                                        onRefresh = { vm.refreshRecipes() },
                                        working = menu.working != null)
                                },
                            ) {
                                GenerateScreen(m?.info, m?.today ?: LocalDate.now(), s.user.people, menu.generating, menu.error,
                                    onGenerate = vm::generateMenu)
                            }
                        }
                    }
                }
            } }
        }
    }
}
