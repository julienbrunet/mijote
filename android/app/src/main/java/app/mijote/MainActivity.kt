package app.mijote

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mijote.ui.AccountsScreen
import app.mijote.ui.InviteScreen
import app.mijote.ui.InvitationsScreen
import app.mijote.ui.LocalImageLoader
import app.mijote.ui.LoginScreen
import app.mijote.ui.MijoteTheme
import app.mijote.ui.NotificationsScreen
import app.mijote.ui.PlannerActions
import app.mijote.ui.PlannerBottomBar
import app.mijote.ui.PlannerScreen
import app.mijote.ui.PreferencesScreen
import app.mijote.ui.RecipeEditorScreen
import app.mijote.ui.RecipeScreen
import app.mijote.ui.RecipesTab
import app.mijote.ui.ServerScreen
import app.mijote.ui.Sunny
import app.mijote.ui.Tab
import app.mijote.ui.UpdateDialog

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
            val notifications by vm.notifications.collectAsStateWithLifecycle()
            val wishSettings by vm.wishSettings.collectAsStateWithLifecycle()
            val week by vm.plannerWeek.collectAsStateWithLifecycle()
            val placement by vm.placement.collectAsStateWithLifecycle()
            val picker by vm.plannerRecipes.collectAsStateWithLifecycle()
            val copy by vm.weekCopy.collectAsStateWithLifecycle()
            val plannerActor = when (val screen = state.screen) {
                is Screen.Home -> screen.user.id
                is Screen.Preferences -> screen.user.id
                is Screen.Admin -> screen.user.id
                else -> null
            }
            val plannerState = key(vm.serverUrl, plannerActor) { rememberSaveableStateHolder() }
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
                            onAccounts = { vm.openAdmin(AdminSection.ACCOUNTS) },
                            notificationCount = notifications.unreadCount, onNotifications = vm::openNotifications,
                            wishSettings = wishSettings, onWishLimit = vm::setWishLimit, onReloadWishSettings = vm::loadWishSettings)
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
                    is Screen.Home -> key(vm.serverUrl, s.user.id) {
                        // Hoisted outside the detail and placement branches so back keeps the exact list anchor.
                        val recipeListState = rememberLazyListState()
                        var filterSheet by rememberSaveable { mutableStateOf(false) }
                        val edited = editor
                        val shown = recipe
                        val placing = placement
                        val showPlanner = placing != null || tab != Tab.RECIPES || menu.proposal != null
                        val selectRecipes = { vm.selectTab(Tab.RECIPES) }
                        when {
                            edited != null -> BackHandler(onBack = vm::closeEditor)
                            shown != null && placing == null -> BackHandler(onBack = vm::closeRecipe)
                            notifications.open && placing == null -> BackHandler(onBack = vm::closeNotifications)
                            !showPlanner -> BackHandler { vm.selectTab(Tab.MENUS) }
                        }
                        when {
                            edited != null -> RecipeEditorScreen(edited, onChange = vm::updateDraft, onSave = vm::saveRecipe, onBack = vm::closeEditor)
                            shown != null && placing == null -> RecipeScreen(shown, s.user.people, onBack = vm::closeRecipe,
                                canWrite = s.user.canWrite && !shown.slotReadOnly, canArchive = s.user.isAdmin && !shown.slotReadOnly,
                                onPlan = { vm.choosePlacement(shown.id, shown.recipe?.title ?: shown.title) },
                                onEdit = { vm.editRecipe(new = false) }, onArchive = vm::archiveRecipe,
                                onFavorite = { vm.toggleFavorite(shown.id) },
                                onWish = if (s.user.isReadonly) vm::requestRecipe else null,
                                onMealPeople = shown.slotId?.takeIf { s.user.canWrite && !shown.slotReadOnly }
                                    ?.let { slot -> { count: Int -> vm.saveMealPeople(slot, count) } })
                            notifications.open && placing == null -> NotificationsScreen(notifications,
                                onBack = vm::closeNotifications, onRefresh = { vm.loadNotifications() },
                                onMore = { vm.loadNotifications(more = true) }, onOpen = vm::openNotification,
                                onRead = vm::readNotification, onDelete = vm::deleteNotification)
                            showPlanner -> plannerState.SaveableStateProvider("planner-timeline") {
                                val displayedWeek = week
                                PlannerScreen(
                                    user = s.user, state = menu, week = displayedWeek, placement = placing, recipes = picker, copy = copy,
                                    unreadNotifications = notifications.unreadCount,
                                    actions = PlannerActions(
                                        onWeek = vm::showPlannerWeek, onRefresh = vm::refreshPlannerWeek,
                                        onLoadBefore = { vm.loadPlannerBoundary(before = true, retry = it) },
                                        onLoadAfter = { vm.loadPlannerBoundary(before = false, retry = it) },
                                        onVisibleDay = vm::trackPlannerWeek,
                                        onCancelPlacement = vm::cancelPlacement,
                                        onChooseRecipe = { vm.choosePlacement(it.id, it.title) },
                                        onPlan = { date, meal, replace -> vm.placeRecipe(date, meal, replace, displayedWeek.snapshot(date)) },
                                        onSearchRecipes = vm::searchPlannerRecipes,
                                        onOpenRecipe = { slot, readOnly ->
                                            slot.recipe?.let { vm.openRecipe(it.id, it.title, slotId = slot.id, slotPeople = slot.people,
                                                slotReadOnly = readOnly) }
                                        },
                                        onGenerate = vm::generateMenu, onSetPeople = vm::setPeople,
                                        onPublish = vm::validateReview, onDiscard = vm::closeReview, onRebase = vm::rebaseReview,
                                        onMove = { source, date, meal -> vm.movePlannerMeal(source, date, meal, displayedWeek.snapshot(date)) },
                                        onUndoMove = vm::undoMealMove, onSwap = { vm.swapMeal(it.id) },
                                        onRemove = { vm.removeMeal(it.id) }, onRename = { slot, title -> vm.renameMeal(slot.id, title) },
                                        onMealPeople = { slot, count -> vm.setMealPeople(slot.id, count) },
                                        onAddRandom = vm::addMeal, onAddManual = vm::addManualMeal,
                                        onCopyPreview = vm::previewWeekCopy, onCopyReplace = vm::setCopyReplace,
                                        onApplyCopy = vm::applyWeekCopy, onCloseCopy = vm::closeWeekCopy,
                                        onRecipes = selectRecipes, onAccount = vm::openPreferences,
                                        onNotifications = vm::openNotifications,
                                    ),
                                )
                            }
                            else -> Column(Modifier.fillMaxSize().background(Sunny.Cream)) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    RecipesTab(recipes, canWrite = s.user.canWrite, listState = recipeListState,
                                        onQuery = { vm.searchRecipes(it, debounce = true) },
                                        onOpen = { vm.openRecipe(it.id, it.title) },
                                        onPlan = { vm.choosePlacement(it.id, it.title) },
                                        onRefresh = { vm.refreshRecipes() }, onPreferences = vm::openPreferences,
                                        onFilters = vm::setRecipeFilters, sheetOpen = filterSheet,
                                        onSheet = { filterSheet = it }, working = menu.working != null)
                                }
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                                    OutlinedButton(onClick = { filterSheet = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text(if (recipes.filters.count > 0) "Filtres (${recipes.filters.count})" else "Filtres")
                                    }
                                    if (s.user.canWrite) Button(onClick = { vm.editRecipe(new = true) },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Sunny.Butter, contentColor = Sunny.Ink)) {
                                        Text("Ajouter une recette")
                                    }
                                }
                                PlannerBottomBar(Tab.RECIPES, { vm.selectTab(Tab.MENUS) }, selectRecipes, vm::openPreferences)
                            }
                        }
                    }
                }
            } }
        }
    }
}
