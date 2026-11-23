package app.mijote

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mijote.data.Api
import app.mijote.data.WishStatus
import app.mijote.data.RecipeNotification
import app.mijote.data.ApiException
import app.mijote.data.AppRelease
import app.mijote.data.MealPlan
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.mijote.data.Menu
import app.mijote.data.MenuSlot
import app.mijote.data.MenuRecipe
import app.mijote.data.SlotVersion
import app.mijote.data.WeekCopy
import app.mijote.data.weekStart
import app.mijote.data.moveProposal
import app.mijote.data.MenuProposal
import app.mijote.data.RecipeDetail
import app.mijote.data.PrefsSessionStore
import app.mijote.data.Session
import app.mijote.data.SessionExpiredException
import app.mijote.data.Updater
import app.mijote.data.User
import app.mijote.data.Account
import app.mijote.data.Invitations
import app.mijote.data.NewInvitation
import app.mijote.data.RecipeDraft
import app.mijote.data.RecipeFilters
import app.mijote.data.RecipeSummary
import app.mijote.ui.Tab
import android.widget.Toast
import app.mijote.data.normalizeInvitationCode
import app.mijote.data.normalizeServerUrl
import app.mijote.data.parseInvitationLink
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the app shows: server address, sign-in, or the signed-in app. */
sealed interface Screen {
    data object Server : Screen
    data class Login(val serverUrl: String) : Screen
    data class Home(val user: User) : Screen
    data class Preferences(val user: User) : Screen

    /** Administration from the app (admin only), opened from the preferences. */
    data class Admin(val user: User, val section: AdminSection) : Screen

    /** Join the family with an invitation; server and code are prefilled when they come from a link. */
    data class Invite(val serverUrl: String? = null, val code: String? = null) : Screen
}

/** Self-update offer, shown as a dialog over any screen. */
sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val release: AppRelease, val error: String? = null) : UpdateState
    data class Downloading(val release: AppRelease) : UpdateState
}

data class UiState(
    val screen: Screen,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Planned meals (server-side generation, FR-GEN). [generated] counts successful generations. */
/** Manual update check from the preferences: [message] tells the result. */
data class UpdateCheck(val checking: Boolean = false, val message: String? = null)

/** Period of the unsaved proposal shown on the review screen. */
data class ReviewPeriod(val from: LocalDate, val to: LocalDate)

data class MealEditOutcome(val requestId: String, val error: String?)

data class MenuState(
    val menu: Menu? = null,
    val loading: Boolean = true,
    val generating: Boolean = false,
    val error: String? = null,
    val generated: Int = 0,
    val review: ReviewPeriod? = null,
    val proposal: MenuProposal? = null,
    /** Slot being changed (swap / remove) or "date|meal" of a meal being added: shows a spinner. */
    val working: String? = null,
    /** A pull-to-refresh is running. */
    val refreshing: Boolean = false,
    val undoMove: MenuProposal? = null,
    val proposalConflict: Boolean = false,
    val mealEditOutcome: MealEditOutcome? = null,
)

data class PlannerWeekState(
    val start: LocalDate? = null,
    val menu: Menu? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val weeks: Map<LocalDate, Menu> = emptyMap(),
    val loadingBefore: Boolean = false,
    val loadingAfter: Boolean = false,
    val beforeError: String? = null,
    val afterError: String? = null,
    val jumpSerial: Int = 0,
    val requestWeek: LocalDate? = null,
) {
    /** Use the snapshot of the selected date for versioned writes, even when the timeline spans several weeks. */
    fun snapshot(date: LocalDate): Menu? {
        val monday = date.weekStart()
        if (monday == (requestWeek ?: start) && (loading || error != null)) return null
        return weeks[monday] ?: menu?.takeIf { monday == start }
    }
}
data class PlacementRecipe(val id: String, val title: String)
data class WeekCopyState(
    val preview: WeekCopy? = null, val from: LocalDate? = null, val to: LocalDate? = null,
    val replaceOccupied: Boolean = false, val loading: Boolean = false, val error: String? = null,
)

/** Recipe detail screen: [recipe] is null while loading. */
data class RecipeState(
    val id: String, val title: String, val recipe: RecipeDetail? = null, val error: String? = null, val busy: Boolean = false,
    /** The planned meal the recipe was opened from: its number of people is shown and saved. */
    val slotId: String? = null,
    val slotPeople: Int? = null,
    val slotReadOnly: Boolean = false,
    val wish: WishStatus? = null, val wishing: Boolean = false, val wishError: String? = null, val wishRevision: Int = 0,
)

data class NotificationsState(
    val items: List<RecipeNotification> = emptyList(), val unreadCount: Int = 0,
    val open: Boolean = false, val loading: Boolean = false, val hasMore: Boolean = false,
    val working: String? = null, val error: String? = null,
)
data class WishSettingsState(val limit: Int? = null, val busy: Boolean = false, val error: String? = null)

/** Recipes tab: [recipes] is null until the first answer. */
data class RecipesState(
    val query: String = "", val recipes: List<RecipeSummary>? = null, val loading: Boolean = false, val error: String? = null,
    /** A pull-to-refresh is running. */
    val refreshing: Boolean = false,
    val filters: RecipeFilters = RecipeFilters(),
) {
    /** The search results that pass the filters. */
    val shown: List<RecipeSummary>? get() = recipes?.filter(filters::matches)
}

/** Recipe editor: [id] is null for a new recipe. */
data class EditorState(val id: String?, val draft: RecipeDraft, val saving: Boolean = false, val error: String? = null)

enum class AdminSection { INVITATIONS, ACCOUNTS }

/** Administration screens: [created] is the invitation just created, shown once with its link and QR code. */
data class AdminState(
    val invitations: Invitations? = null,
    val accounts: List<Account>? = null,
    val created: NewInvitation? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val session = Session(PrefsSessionStore(app))

    private val _state = MutableStateFlow(UiState(initialScreen()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val updater = Updater(app)
    private val _update = MutableStateFlow<UpdateState>(UpdateState.None)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    val currentVersion get() = updater.currentVersion

    private val _check = MutableStateFlow(UpdateCheck())
    val updateCheck: StateFlow<UpdateCheck> = _check.asStateFlow()
    val serverUrl: String get() = session.serverUrl.orEmpty()

    private val _menu = MutableStateFlow(MenuState())
    val menu: StateFlow<MenuState> = _menu.asStateFlow()

    private val _plannerWeek = MutableStateFlow(PlannerWeekState(jumpSerial = UUID.randomUUID().hashCode()))
    val plannerWeek: StateFlow<PlannerWeekState> = _plannerWeek.asStateFlow()
    private val _placement = MutableStateFlow<PlacementRecipe?>(null)
    val placement: StateFlow<PlacementRecipe?> = _placement.asStateFlow()
    private val _plannerRecipes = MutableStateFlow(RecipesState())
    val plannerRecipes: StateFlow<RecipesState> = _plannerRecipes.asStateFlow()
    private val _weekCopy = MutableStateFlow(WeekCopyState())
    val weekCopy: StateFlow<WeekCopyState> = _weekCopy.asStateFlow()
    private val cachedWeeks = LinkedHashMap<LocalDate, Menu>()
    private var weekJob: Job? = null
    private var beforeWeekJob: Job? = null
    private var afterWeekJob: Job? = null
    private var visiblePlannerWeek: LocalDate? = null
    private var plannerWriteRevision = 0L
    private var mealEditJob: Job? = null
    private var copyJob: Job? = null
    private var pickerJob: Job? = null
    private var preferenceJob: Job? = null
    private var preferenceRevision = 0
    private data class GenerationRequest(val start: LocalDate, val meals: Int, val plan: MealPlan)
    private var generationRequest: GenerationRequest? = null

    private val _notifications = MutableStateFlow(NotificationsState())
    val notifications: StateFlow<NotificationsState> = _notifications.asStateFlow()
    private val _wishSettings = MutableStateFlow(WishSettingsState())
    val wishSettings: StateFlow<WishSettingsState> = _wishSettings.asStateFlow()
    private var notificationsFromPreferences = false

    private val _recipe = MutableStateFlow<RecipeState?>(null)
    val recipe: StateFlow<RecipeState?> = _recipe.asStateFlow()

    private val _tab = MutableStateFlow(Tab.MENUS)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _recipes = MutableStateFlow(RecipesState())
    val recipes: StateFlow<RecipesState> = _recipes.asStateFlow()

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private val _admin = MutableStateFlow(AdminState())
    val admin: StateFlow<AdminState> = _admin.asStateFlow()

    // Thumbnails are small (see the server): a bitmap cache of a few MB is plenty.
    private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Downloads (with the access token) and caches a recipe thumbnail; null when missing or offline. */
    suspend fun image(path: String): Bitmap? {
        images.get(path)?.let { return it }
        val bytes = try {
            session.authed { api, access -> api.image(access, path) }
        } catch (_: Exception) {
            return null
        } ?: return null
        val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return null
        images.put(path, bmp)
        return bmp
    }

    init {
        // Release builds look for a newer published version at launch; debug builds never do.
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            viewModelScope.launch {
                val release = runCatching { withContext(Dispatchers.IO) { updater.check() } }.getOrNull()
                if (release != null) _update.value = UpdateState.Available(release)
            }
        }
        // Screens render from local state immediately; the session is checked in the background.
        if (_state.value.screen is Screen.Home) {
            viewModelScope.launch {
                try {
                    val u = session.refreshUser()
                    _state.update { if (it.screen is Screen.Home) it.copy(screen = Screen.Home(u)) else it }
                    loadMenu()
                } catch (e: SessionExpiredException) {
                    toLogin("Votre session a expiré : reconnectez-vous.")
                } catch (_: IOException) {
                    loadMenu()
                }
            }
        }
    }

    private fun initialScreen(): Screen {
        val url = session.serverUrl ?: return Screen.Server
        val user = session.user ?: return Screen.Login(url)
        return Screen.Home(user)
    }

    private fun toLogin(error: String? = null) {
        resetData()
        val url = session.serverUrl
        _state.value = UiState(if (url == null) Screen.Server else Screen.Login(url), error = error)
    }

    private fun run(action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, error = errorMessage(e)) }
            }
        }
    }

    fun submitServer(input: String) {
        val url = normalizeServerUrl(input)
        if (url == null) {
            _state.update { it.copy(error = "Adresse invalide : saisissez une adresse https, par exemple mesrepas.exemple.fr") }
            return
        }
        run {
            session.setServer(url)
            _state.value = UiState(Screen.Login(url))
        }
    }

    fun changeServer() {
        _state.value = UiState(Screen.Server)
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) {
            _state.update { it.copy(error = "Saisissez votre identifiant et votre mot de passe.") }
            return
        }
        run {
            val user = session.login(username.trim(), password, "${Build.MANUFACTURER} ${Build.MODEL}")
            _state.value = UiState(Screen.Home(user))
            loadMenu()
        }
    }

    /** An invitation link was opened (QR code scanned with the camera, message…). */
    fun openInvitationLink(link: String?) {
        if (_state.value.screen is Screen.Home || _state.value.screen is Screen.Preferences || _state.value.screen is Screen.Admin) return // already signed in: never replace the session by accident
        val parsed = parseInvitationLink(link)
        _state.value = if (parsed != null) UiState(Screen.Invite(parsed.serverUrl, parsed.code))
        else UiState(_state.value.screen, error = "Ce lien d'invitation n'est pas valide.")
    }

    /** "I have an invitation" without a link: the server and the code are typed by hand. */
    fun showInvite() {
        _state.value = UiState(Screen.Invite())
    }

    fun backFromInvite() = toLogin()

    fun redeemInvitation(
        server: String, code: String, username: String, displayName: String, password: String, confirm: String,
    ) {
        val url = normalizeServerUrl(server)
        val cleanCode = normalizeInvitationCode(code)
        val error = when {
            url == null -> "Adresse invalide : saisissez une adresse https, par exemple mesrepas.exemple.fr"
            cleanCode == null -> "Le code d'invitation est incomplet ou invalide."
            username.isBlank() -> "Choisissez un identifiant."
            password.length < 8 -> "Le mot de passe doit faire au moins 8 caractères."
            password != confirm -> "Les deux saisies du mot de passe diffèrent."
            else -> null
        }
        if (error != null || url == null || cleanCode == null) {
            _state.update { it.copy(error = error) }
            return
        }
        run {
            val user = session.redeemInvitation(url, cleanCode, username.trim(), displayName.trim(), password,
                "${Build.MANUFACTURER} ${Build.MODEL}")
            _state.value = UiState(Screen.Home(user))
            loadMenu()
        }
    }

    /** Reloads the planned meals; failures keep what is shown (offline reading). */
    fun loadMenu(manual: Boolean = false) {
        if (_menu.value.working != null || _menu.value.refreshing) return
        val actor = signedInUser()?.id ?: return
        val address = serverUrl
        val before = _menu.value.menu
        loadNotifications()
        _menu.update { it.copy(loading = it.menu == null, refreshing = true, error = null) }
        viewModelScope.launch {
            try {
                val m = session.authed { api, access -> api.menu(access) }
                if (signedInUser()?.id != actor || serverUrl != address) return@launch
                _menu.update { if (it.menu === before && it.working == null) it.copy(menu = m, loading = false, refreshing = false, error = null)
                    else it.copy(loading = false, refreshing = false) }
                if (_menu.value.working == null) {
                    if (_plannerWeek.value.start == null) showPlannerWeek(m.today)
                    else refreshVisiblePlannerWeek()
                }
            } catch (e: SessionExpiredException) {
                if (signedInUser()?.id != actor || serverUrl != address) return@launch
                _menu.update { it.copy(loading = false, refreshing = false) }
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                if (signedInUser()?.id != actor || serverUrl != address) return@launch
                // Keep an existing menu available offline and report failed requests.
                _menu.update { it.copy(loading = false, refreshing = false, error = errorMessage(e)) }
            }
        }
    }

    /** Explicit navigation scrolls to this week. Edges of the timeline load independently. */
    fun showPlannerWeek(date: LocalDate) {
        if (signedInUser() == null) return
        val start = date.weekStart()
        weekJob?.cancel()
        beforeWeekJob?.cancel()
        afterWeekJob?.cancel()
        val previous = _plannerWeek.value
        val withinTimeline = start in previous.weeks
        val weeks = if (withinTimeline) previous.weeks else
            cachedWeeks[start]?.let { mapOf(start to it) }.orEmpty()
        visiblePlannerWeek = start
        _plannerWeek.value = PlannerWeekState(start, weeks[start], loading = true, weeks = weeks,
            beforeError = previous.beforeError.takeIf { withinTimeline },
            afterError = previous.afterError.takeIf { withinTimeline },
            jumpSerial = previous.jumpSerial + 1, requestWeek = start)
        weekJob = requestPlannerWeek(start)
    }

    /** Extend exactly one edge by one week. Failures need an explicit retry at that edge. */
    fun loadPlannerBoundary(before: Boolean, retry: Boolean = false) {
        if (signedInUser() == null || _menu.value.working != null) return
        val state = _plannerWeek.value
        if (state.loading || state.weeks.isEmpty()) return
        val busy = if (before) state.loadingBefore else state.loadingAfter
        val failure = if (before) state.beforeError else state.afterError
        if (busy || (failure != null && !retry)) return
        val target = if (before) state.weeks.keys.minOrNull()!!.minusWeeks(1)
            else state.weeks.keys.maxOrNull()!!.plusWeeks(1)
        _plannerWeek.update { if (before) it.copy(loadingBefore = true, beforeError = null)
            else it.copy(loadingAfter = true, afterError = null) }
        if (before) beforeWeekJob = requestPlannerWeek(target, before = true)
        else afterWeekJob = requestPlannerWeek(target, before = false)
    }

    fun trackPlannerWeek(date: LocalDate) {
        val start = date.weekStart()
        if (start in _plannerWeek.value.weeks) visiblePlannerWeek = start
    }

    private fun refreshVisiblePlannerWeek(date: LocalDate? = null) {
        val state = _plannerWeek.value
        val page = date?.weekStart()?.takeIf { it in state.weeks }
            ?: visiblePlannerWeek?.takeIf { it in state.weeks } ?: state.start ?: return
        weekJob?.cancel()
        _plannerWeek.update { it.copy(loading = true, error = null, requestWeek = page) }
        weekJob = requestPlannerWeek(page)
    }

    private fun requestPlannerWeek(page: LocalDate, before: Boolean? = null): Job {
        val requester = signedInUser()?.id
        val address = serverUrl
        val serial = _plannerWeek.value.jumpSerial
        val writeRevision = plannerWriteRevision
        return viewModelScope.launch {
            fun current() = isActive && requester != null && requester == signedInUser()?.id &&
                address == serverUrl && _plannerWeek.value.jumpSerial == serial

            fun finish(fetched: Menu? = null, message: String? = null) {
                _plannerWeek.update { state ->
                    val pages = if (fetched == null) state.weeks else state.weeks + (page to fetched)
                    val updated = state.copy(weeks = pages, menu = state.start?.let(pages::get))
                    when (before) {
                        true -> updated.copy(loadingBefore = false, beforeError = message)
                        false -> updated.copy(loadingAfter = false, afterError = message)
                        null -> updated.copy(loading = false, error = message, requestWeek = page.takeIf { message != null })
                    }
                }
            }

            try {
                val week = session.authed { api, access -> api.menu(access, page, page.plusDays(6)) }
                if (!current()) return@launch
                if (plannerWriteRevision != writeRevision) {
                    finish()
                    return@launch
                }
                cachedWeeks.remove(page)
                cachedWeeks[page] = week
                if (cachedWeeks.size > 12) cachedWeeks.remove(cachedWeeks.keys.first())
                finish(week)
            } catch (e: SessionExpiredException) {
                if (current()) toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                if (current()) finish(message = errorMessage(e))
            }
        }
    }

    fun refreshPlannerWeek() = refreshPlannerWeek(null)

    private fun refreshPlannerWeek(date: LocalDate?) {
        val start = _plannerWeek.value.start
        if (start == null || _menu.value.menu == null) loadMenu(manual = true)
        else if (_menu.value.working == null) {
            loadNotifications()
            refreshVisiblePlannerWeek(date)
        }
    }

    fun choosePlacement(id: String, title: String) {
        if (signedInUser()?.canWrite != true || _menu.value.working != null) return
        _placement.value = PlacementRecipe(id, title)
        val today = _menu.value.menu?.today ?: _plannerWeek.value.menu?.today ?: return
        val week = visiblePlannerWeek ?: _plannerWeek.value.start
        val draft = _menu.value.proposal
        if (week == null || (draft != null && (week > draft.to || week.plusDays(6) < draft.from))) {
            showPlannerWeek(draft?.from ?: today)
        } else if (draft == null && (week.plusDays(6) < today || week > today.plusDays(20))) {
            showPlannerWeek(today)
        }
    }

    fun cancelPlacement() {
        if (_menu.value.working != null) return
        _placement.value = null
    }

    fun searchPlannerRecipes(query: String) {
        if (signedInUser()?.canWrite != true) return
        pickerJob?.cancel()
        _plannerRecipes.update { it.copy(query = query, loading = true, error = null) }
        pickerJob = viewModelScope.launch {
            delay(220)
            try {
                val recipes = session.authed { api, access -> api.recipes(access, query) }
                _plannerRecipes.value = RecipesState(query, recipes)
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _plannerRecipes.update { it.copy(loading = false, error = errorMessage(e)) }
            }
        }
    }

    fun previewWeekCopy(source: LocalDate, destination: LocalDate) {
        if (signedInUser()?.canWrite != true || _menu.value.proposal != null || _menu.value.working != null) return
        copyJob?.cancel()
        val from = source.weekStart()
        val to = destination.weekStart()
        if (from == to) {
            _weekCopy.value = WeekCopyState(from = from, to = to, error = "Choisissez une autre semaine à copier.")
            return
        }
        _weekCopy.value = WeekCopyState(from = from, to = to, loading = true)
        copyJob = viewModelScope.launch {
            try {
                val preview = session.authed { api, access ->
                    val week = api.menu(access, from, from.plusDays(6))
                    api.weekCopy(access, week, from, to)
                }
                _weekCopy.update { it.copy(preview = preview, loading = false) }
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _weekCopy.update { it.copy(loading = false, error = errorMessage(e)) }
            }
        }
    }

    fun setCopyReplace(replace: Boolean) {
        if (!_weekCopy.value.loading) _weekCopy.update { it.copy(replaceOccupied = replace) }
    }

    fun closeWeekCopy() {
        if (_menu.value.working == "week-copy") return
        copyJob?.cancel()
        _weekCopy.value = WeekCopyState()
    }

    fun applyWeekCopy() {
        val state = _weekCopy.value
        val preview = state.preview ?: return
        val to = state.to ?: return
        val proposal = preview.plan(state.replaceOccupied).proposal ?: return
        if (state.loading || _menu.value.working != null || signedInUser()?.canWrite != true) return
        _menu.update { it.copy(working = "week-copy", error = null, undoMove = null) }
        _weekCopy.update { it.copy(loading = true, error = null) }
        copyJob = viewModelScope.launch {
            try {
                val saved = session.authed { api, access -> api.confirmMenu(access, proposal) }
                updateSharedPeriod(proposal, saved)
                _weekCopy.value = WeekCopyState()
                _menu.update { it.copy(working = null) }
                showPlannerWeek(to)
                notice("Repas copiés sur la semaine choisie.")
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _menu.update { it.copy(working = null) }
                val conflict = e is ApiException && e.code == "menu_changed"
                _weekCopy.update { it.copy(preview = if (conflict) null else it.preview, loading = false,
                    error = if (conflict) "La semaine a changé. Recalculez la copie avant de valider." else errorMessage(e)) }
            }
        }
    }

    /**
     * The app comes back to the foreground: what other members changed meanwhile is fetched
     * (FR-SHR-02, no real-time). Nothing runs while the app is in the background.
     */
    fun onAppForeground() {
        if (_state.value.screen is Screen.Preferences) { loadNotifications(); loadWishSettings(); return }
        if (_state.value.screen !is Screen.Home) return
        loadMenu()
        if (_recipes.value.recipes != null) refreshRecipes(manual = false)
        _recipe.value?.let { r -> if (r.recipe != null && _editor.value == null) openRecipe(r.id, r.title, keep = true) }
    }

    /** Reloads the recipe list with the current search (pull-to-refresh or return to the app). */
    fun refreshRecipes(manual: Boolean = true) = searchRecipes(_recipes.value.query, manual = manual)

    fun openPreferences() {
        loadWishSettings()
        val home = _state.value.screen as? Screen.Home ?: return
        _check.value = UpdateCheck()
        _state.value = UiState(Screen.Preferences(home.user))
    }

    fun closePreferences() {
        val p = _state.value.screen as? Screen.Preferences ?: return
        _state.value = UiState(Screen.Home(p.user))
    }

    /** "Change server": signs out, then asks for the new address. */
    fun leaveServer() {
        viewModelScope.launch {
            session.logout()
            resetData()
            _state.value = UiState(Screen.Server)
        }
    }

    /** Looks for a newer published version, also in debug builds; a found release opens the update dialog. */
    fun checkForUpdate() {
        if (getApplication<Application>().packageName == "app.mijote.uxpreview") {
            _check.value = UpdateCheck(message = "Cette version d’essai est mise à jour par un nouvel APK.")
            return
        }
        if (_check.value.checking) return
        _check.value = UpdateCheck(checking = true)
        viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { updater.check() }
                if (release != null) {
                    _update.value = UpdateState.Available(release)
                    _check.value = UpdateCheck(message = "Version ${release.version} disponible.")
                } else {
                    _check.value = UpdateCheck(message = "L'application est à jour.")
                }
            } catch (_: IOException) {
                _check.value = UpdateCheck(message = "Impossible de vérifier les mises à jour : vérifiez la connexion.")
            }
        }
    }

    /** Saves the number of people; the screen shows the new value at once and rolls back on failure. */
    /** From the preferences or the planner's composition controls. */
    fun setPeople(people: Int) {
        val screen = _state.value.screen
        val before = when (screen) {
            is Screen.Preferences -> screen.user
            is Screen.Home -> screen.user
            else -> return
        }
        if (!before.canWrite || people !in 1..20 || before.people == people) return
        fun withUser(s: Screen, u: User) = when (s) {
            is Screen.Preferences -> Screen.Preferences(u)
            is Screen.Home -> Screen.Home(u)
            else -> s
        }
        _state.update { it.copy(screen = withUser(it.screen, before.copy(people = people))) }
        val previous = preferenceJob
        val revision = ++preferenceRevision
        preferenceJob = viewModelScope.launch {
            previous?.join()
            try {
                val u = session.authed { api, access -> api.setPeople(access, people) }
                session.updateUser(u)
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                if (revision == preferenceRevision) {
                    val saved = session.user ?: before
                    _state.update { it.copy(screen = withUser(it.screen, saved), error = errorMessage(e)) }
                    _menu.update { it.copy(error = errorMessage(e)) }
                }
            }
        }
    }

    fun generateMenu(start: LocalDate, meals: Int, plan: MealPlan) {
        if (_menu.value.generating || _menu.value.working != null || signedInUser()?.canWrite != true) return
        generationRequest = GenerationRequest(start, meals, plan)
        _menu.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            preferenceJob?.join()
            try {
                val proposal = session.authed { api, access -> api.previewMenu(access, start, meals, plan) }
                _menu.update { it.copy(generating = false, proposal = proposal, review = ReviewPeriod(proposal.from, proposal.to),
                    proposalConflict = false, undoMove = null) }
                _tab.value = Tab.MENUS
                showPlannerWeek(proposal.from)
            } catch (e: SessionExpiredException) {
                _menu.update { it.copy(generating = false) }
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _menu.update { it.copy(generating = false, error = errorMessage(e)) }
            }
        }
    }

    /** Leaving the review discards its local draft, without touching planned meals. */
    fun closeReview() {
        if (_menu.value.working != null || _menu.value.generating) return
        _placement.value = null
        _menu.update { it.copy(review = null, proposal = null, error = null, proposalConflict = false) }
    }

    fun rebaseReview() {
        if (!_menu.value.proposalConflict) return
        generationRequest?.let { generateMenu(it.start, it.meals, it.plan) }
    }

    fun validateReview() {
        val proposal = _menu.value.proposal ?: return
        if (_menu.value.working != null || _menu.value.generating || _menu.value.proposalConflict || signedInUser()?.canWrite != true) return
        _menu.update { it.copy(working = "confirm", error = null) }
        viewModelScope.launch {
            try {
                val saved = session.authed { api, access -> api.confirmMenu(access, proposal) }
                updateSharedPeriod(proposal, saved)
                _menu.update { state ->
                    state.copy(proposal = null, review = null, working = null, proposalConflict = false, generated = state.generated + 1)
                }
                refreshPlannerWeek()
                notice("Menu publié.")
            } catch (e: SessionExpiredException) {
                _menu.update { it.copy(working = null) }; toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _menu.update { it.copy(working = null, error = errorMessage(e), proposalConflict = e is ApiException && e.code == "menu_changed") }
            }
        }
    }

    private fun editProposal(edit: (MenuProposal) -> MenuProposal) {
        if (_menu.value.working != null || _menu.value.proposalConflict || signedInUser()?.canWrite != true) return
        _menu.update { state -> state.copy(proposal = state.proposal?.let(edit), error = null) }
    }

    private fun draftSlot(id: String, edit: (MenuSlot) -> MenuSlot) = editProposal { proposal ->
        proposal.copy(slots = proposal.slots.map { if (it.id == id) edit(it) else it })
    }

    private fun draftRecipe(date: LocalDate, meal: String, existing: MenuSlot? = null) {
        val proposal = _menu.value.proposal ?: return
        if (_menu.value.working != null || _menu.value.proposalConflict || signedInUser()?.canWrite != true) return
        _menu.update { it.copy(working = existing?.id ?: "$date|$meal", error = null) }
        viewModelScope.launch {
            try {
                val recipe = session.authed { api, access -> api.previewRecipe(access, date, meal, proposal.slots, existing?.recipe?.id) }
                val slot = MenuSlot(existing?.id ?: java.util.UUID.randomUUID().toString(), date, meal, recipe, false,
                    people = existing?.people ?: (_state.value.screen as? Screen.Home)?.user?.people ?: 1)
                _menu.update { it.copy(proposal = it.proposal?.withSlot(slot), working = null) }
            } catch (e: SessionExpiredException) {
                _menu.update { it.copy(working = null) }; toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _menu.update { it.copy(working = null, error = errorMessage(e)) }
            }
        }
    }

    /** Update the displayed snapshots immediately from the server's confirmed period. */
    private fun updateSharedPeriod(proposal: MenuProposal, saved: List<MenuSlot>) {
        plannerWriteRevision++
        fun Menu.updated(replacements: List<MenuSlot>) = copy(slots =
            (slots.filter { it.date < proposal.from || it.date > proposal.to } + replacements)
                .sortedWith(compareBy<MenuSlot> { it.date }.thenBy { it.meal != "lunch" }))
        fun Menu.updatedWeek(first: LocalDate): Menu {
            val last = first.plusDays(6)
            if (last < proposal.from || first > proposal.to) return this
            return updated(saved.filter { it.date in first..last })
        }
        _menu.update { state -> state.copy(menu = state.menu?.let { menu ->
            menu.updated(saved.filter { it.date >= menu.today })
        }) }
        for ((first, week) in cachedWeeks.toMap()) {
            cachedWeeks[first] = week.updatedWeek(first)
        }
        _plannerWeek.update { state ->
            val weeks = state.weeks.mapValues { (first, week) -> week.updatedWeek(first) }
            state.copy(weeks = weeks, menu = state.start?.let(weeks::get))
        }
    }

    private fun plannerSlotDate(id: String): LocalDate? = _plannerWeek.value.weeks.values.firstNotNullOfOrNull { week ->
        week.slots.firstOrNull { it.id == id }?.date
    }

    /** Saves the content and portions together, against the slot seen when the editing sheet opened. */
    fun savePlannerMeal(requestId: String, date: LocalDate, meal: String, expected: SlotVersion?, replacement: MenuSlot?) {
        val state = _menu.value
        val operation = "meal-edit:$requestId"
        if (state.working == operation) return

        fun reject(message: String) {
            _menu.update { it.copy(mealEditOutcome = MealEditOutcome(requestId, message)) }
        }

        val user = signedInUser()?.takeIf { it.canWrite }
        if (user == null) {
            reject("Action non autorisée pour votre compte.")
            return
        }
        val title = replacement?.title?.trim()
        val draft = state.proposal
        val snapshot = _plannerWeek.value.snapshot(date)
        val knownToday = listOfNotNull(state.menu?.today, _plannerWeek.value.menu?.today, snapshot?.today).maxOrNull()
        val problem = when {
            state.working != null || state.generating || state.refreshing || _weekCopy.value.loading ->
                "Une opération est en cours. Réessayez dans un instant."
            state.proposalConflict -> "Le menu a changé. Recalculez la proposition avant de la modifier."
            meal != "lunch" && meal != "dinner" -> "Choisissez un créneau de midi ou du soir."
            knownToday != null && date < knownToday -> "Ce repas est passé."
            draft != null && date !in draft.from..draft.to -> "Ce repas ne fait pas partie de la proposition."
            replacement != null && (replacement.date != date || replacement.meal != meal) ->
                "Le créneau du repas ne correspond pas."
            title != null && replacement?.recipe != null -> "Choisissez une recette ou un intitulé libre."
            title != null && title.codePointCount(0, title.length) !in 1..100 ->
                "L’intitulé doit contenir entre 1 et 100 caractères."
            replacement?.recipe?.id?.isBlank() == true -> "Choisissez une recette."
            replacement?.people?.let { it !in 1..20 } == true ->
                "Le nombre de personnes doit être compris entre 1 et 20."
            else -> null
        }
        if (problem != null) {
            reject(problem)
            return
        }

        fun matches(slot: MenuSlot?) = expected == slot?.let { SlotVersion(it.id, it.version) }
        fun missingContent(current: MenuSlot?) = replacement != null && title == null && replacement.recipe == null &&
            (current == null || current.title != null || current.recipe != null)
        fun updatedSlot(current: MenuSlot?): MenuSlot? = replacement?.let { slot ->
            slot.copy(
                id = current?.id ?: slot.id,
                recipe = current?.recipe?.takeIf { it.id == slot.recipe?.id } ?: slot.recipe,
                title = title,
                people = slot.people ?: if (current == null) user.people else current.people,
                locked = title != null || slot.locked,
                version = current?.version ?: 0,
            )
        }
        val changed = if (expected == null) "Ce créneau est déjà occupé. Choisissez un autre créneau."
            else "Ce créneau a changé. Fermez la fiche et ouvrez-la à nouveau."
        val chooseContent = "Choisissez une recette ou saisissez un intitulé."
        if (draft != null) {
            val current = draft.slots.firstOrNull { it.date == date && it.meal == meal }
            if (!matches(current)) {
                reject(changed)
                return
            }
            if (missingContent(current)) {
                reject(chooseContent)
                return
            }
            val updated = updatedSlot(current)
            val edited = when {
                current == updated -> draft
                updated == null -> draft.copy(slots = draft.slots.filterNot { it.date == date && it.meal == meal })
                else -> draft.withSlot(updated.copy(version = updated.version + 1))
            }
            _menu.update { it.copy(proposal = edited, error = null, mealEditOutcome = MealEditOutcome(requestId, null)) }
            return
        }

        val address = serverUrl
        _menu.update { it.copy(working = operation, error = null, mealEditOutcome = null) }
        mealEditJob = viewModelScope.launch {
            fun currentRequest() = isActive && signedInUser()?.id == user.id &&
                serverUrl == address && _menu.value.working == operation
            fun complete(error: String? = null, clearUndo: Boolean = false) {
                _menu.update { it.copy(working = null, undoMove = it.undoMove.takeUnless { clearUndo },
                    mealEditOutcome = MealEditOutcome(requestId, error)) }
            }
            try {
                // ConfirmMenu compares every saved version in the period. Read the latest sibling meal
                // and the server's date, while still requiring the original version of the edited slot.
                val day = session.authed { api, access -> api.menu(access, date, date) }
                if (!currentRequest()) return@launch
                val slots = day.slots.filter { it.date == date }
                val current = slots.firstOrNull { it.meal == meal }
                val failure = when {
                    date < day.today -> "Ce repas est passé."
                    !matches(current) -> changed
                    current == null && replacement != null && date > day.today.plusDays(20) ->
                        "Vous pouvez ajouter un repas dans les 21 prochains jours."
                    missingContent(current) -> chooseContent
                    else -> null
                }
                if (failure != null) {
                    complete(failure)
                    refreshPlannerWeek(date)
                    return@launch
                }
                val updated = updatedSlot(current)
                if (current == updated) {
                    complete()
                    return@launch
                }
                val proposal = MenuProposal(date, date, slots, slots.map { SlotVersion(it.id, it.version) })
                val edit = if (updated == null) proposal.copy(slots = slots.filterNot { it.meal == meal })
                    else proposal.withSlot(updated)
                val selectedRecipe = updated?.recipe?.id
                val isSiblingRecipe = selectedRecipe != null && selectedRecipe != current?.recipe?.id &&
                    slots.any { it.recipe?.id == selectedRecipe }
                val saved = session.authed { api, access ->
                    // The server permits existing recipes in a confirmation even if archived. A recipe
                    // already used in the other meal still needs to be active when choosing it here.
                    if (selectedRecipe != null && isSiblingRecipe && api.recipe(access, selectedRecipe).archived) {
                        throw ApiException(422, "validation_error", "Cette recette est archivée.")
                    }
                    api.confirmMenu(access, edit)
                }
                if (!currentRequest()) return@launch
                updateSharedPeriod(edit, saved)
                complete(clearUndo = true)
                refreshPlannerWeek(date)
            } catch (e: SessionExpiredException) {
                if (currentRequest()) toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                if (!currentRequest()) return@launch
                complete(if (e is ApiException && e.code == "menu_changed") changed else errorMessage(e))
                refreshPlannerWeek(date)
            }
        }
    }

    /** Runs a change of the planned meals, then reloads the menu; [done] is told on success. */
    private fun change(
        key: String, done: String? = null, onSaved: () -> Unit = {}, date: LocalDate? = plannerSlotDate(key),
        action: (Api, String) -> Unit,
    ) {
        if (_menu.value.working != null || signedInUser()?.canWrite != true) return
        _menu.update { it.copy(working = key, error = null, undoMove = null) }
        viewModelScope.launch {
            preferenceJob?.join()
            try {
                session.authed { api, access -> action(api, access) }
                plannerWriteRevision++
                onSaved()
                done?.let { notice(it) }
                val m = session.authed { api, access -> api.menu(access) }
                _menu.update { it.copy(menu = m, working = null) }
                refreshPlannerWeek(date)
            } catch (e: SessionExpiredException) {
                _menu.update { it.copy(working = null) }
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _menu.update { it.copy(working = null, error = errorMessage(e)) }
                refreshPlannerWeek(date)
            }
        }
    }

    fun movePlannerMeal(source: MenuSlot, date: LocalDate, meal: String, snapshot: Menu?) {
        val draft = _menu.value.proposal
        if (draft != null) {
            val target = draft.slots.find { it.date == date && it.meal == meal } ?: return
            editProposal { it.move(source.id, target.id) }
            return
        }
        if (signedInUser()?.canWrite != true || _menu.value.refreshing || _menu.value.working != null) return
        if (snapshot == null || snapshot != _plannerWeek.value.snapshot(date)) {
            _menu.update { it.copy(error = "La semaine a changé. Choisissez à nouveau la destination.") }
            return
        }
        val today = maxOf(_menu.value.menu?.today ?: snapshot.today, snapshot.today)
        val from = minOf(date, source.date)
        val to = maxOf(date, source.date)
        if (from < today || to >= from.plusDays(60)) return
        val target = snapshot.slots.find { it.date == date && it.meal == meal }
        val targetId = target?.id ?: "$date|$meal"
        if (from.weekStart() == to.weekStart()) {
            if (snapshot.slots.none { it.id == source.id && it.version == source.version }) return
            val proposal = snapshot.moveProposal(source.id, targetId) ?: return
            saveHomeMove(proposal, rememberUndo = true, before = snapshot)
            return
        }
        _menu.update { it.copy(working = "move", error = null) }
        viewModelScope.launch {
            try {
                val range = session.authed { api, access -> api.menu(access, from, to) }
                val now = range.slots.find { it.date == date && it.meal == meal }
                val matchingSource = range.slots.any { it.id == source.id && it.version == source.version }
                _menu.update { it.copy(working = null) }
                if (!matchingSource || now?.id != target?.id || now?.version != target?.version || from < range.today) {
                    _menu.update { it.copy(error = "Des repas ont changé sur cette période. Choisissez à nouveau les créneaux.") }
                    refreshPlannerWeek()
                } else {
                    range.moveProposal(source.id, targetId)?.let { saveHomeMove(it, rememberUndo = true, before = range) }
                }
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _menu.update { it.copy(working = null, error = errorMessage(e)) }
            }
        }
    }

    fun undoMealMove() {
        val proposal = _menu.value.undoMove ?: return
        if ((_state.value.screen as? Screen.Home)?.user?.canWrite != true) return
        saveHomeMove(proposal, rememberUndo = false)
    }

    private fun saveHomeMove(proposal: MenuProposal, rememberUndo: Boolean, before: Menu? = null) {
        if (_menu.value.working != null || _menu.value.refreshing) return
        if (rememberUndo && before == null) return
        _menu.update { it.copy(working = "move", error = null, undoMove = null) }
        viewModelScope.launch {
            try {
                val saved = session.authed { api, access -> api.confirmMenu(access, proposal) }
                val undo = if (rememberUndo && before != null) MenuProposal(proposal.from, proposal.to,
                    before.slots.filter { it.date >= proposal.from && it.date <= proposal.to },
                    saved.map { SlotVersion(it.id, it.version) }) else null
                updateSharedPeriod(proposal, saved)
                _menu.update { it.copy(working = null, undoMove = undo) }
                refreshPlannerWeek()
                if (undo != null) {
                    kotlinx.coroutines.delay(8000)
                    _menu.update { if (it.undoMove === undo) it.copy(undoMove = null) else it }
                }
            } catch (e: SessionExpiredException) {
                _menu.update { it.copy(working = null) }; toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                val conflict = e is ApiException && e.code == "menu_changed"
                _menu.update { it.copy(working = null, error = if (conflict)
                    "Le menu a changé sur un autre appareil. Le déplacement n’a pas été enregistré ; actualisez le menu."
                    else errorMessage(e)) }
            }
        }
    }

    fun swapMeal(slotId: String) {
        val proposal = _menu.value.proposal
        if (proposal != null) {
            proposal.slots.find { it.id == slotId }?.let { draftRecipe(it.date, it.meal, it) }
        } else change(slotId) { api, access -> api.swapSlot(access, slotId) }
    }

    fun addMeal(date: LocalDate, meal: String) {
        if (_menu.value.proposal != null) draftRecipe(date, meal)
        else change("$date|$meal", date = date) { api, access -> api.addSlot(access, date, meal) }
    }

    fun removeMeal(slotId: String) {
        if (_menu.value.proposal != null) editProposal { it.copy(slots = it.slots.filterNot { slot -> slot.id == slotId }) }
        else change(slotId) { api, access -> api.removeSlot(access, slotId) }
    }

    fun setMealPeople(slotId: String, people: Int) {
        if (_menu.value.proposal != null) draftSlot(slotId) { it.copy(people = people.coerceIn(1,20)) }
        else change(slotId) { api, access -> api.setSlotPeople(access, slotId, people) }
    }

    private var peopleJob: kotlinx.coroutines.Job? = null

    /**
     * The stepper of a recipe opened from a planned meal: the meal's number of people changes at
     * once on screen and is saved after a short pause (several taps make one request).
     */
    fun saveMealPeople(slotId: String, people: Int) {
        _recipe.update { if (it?.slotId == slotId) it.copy(slotPeople = people) else it }
        if (_menu.value.proposal != null) {
            setMealPeople(slotId, people)
            return
        }
        _menu.update { s -> s.copy(menu = s.menu?.let { m -> m.copy(slots = m.slots.map { if (it.id == slotId) it.copy(people = people) else it }) }) }
        peopleJob?.cancel()
        peopleJob = viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            try {
                session.authed { api, access -> api.setSlotPeople(access, slotId, people) }
                plannerWriteRevision++
                refreshPlannerWeek(plannerSlotDate(slotId))
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                notice(errorMessage(e))
                loadMenu()
            }
        }
    }

    fun addManualMeal(date: LocalDate, meal: String, title: String) {
        val proposal = _menu.value.proposal ?: return
        if (!validDraftTitle(title) || date !in proposal.from..proposal.to) return
        val existing = proposal.slots.find { it.date == date && it.meal == meal }
        editProposal { it.withSlot(MenuSlot(existing?.id ?: UUID.randomUUID().toString(), date, meal, null, true,
            title = title.trim(), people = existing?.people ?: signedInUser()?.people ?: 1)) }
    }

    fun placeRecipe(date: LocalDate, meal: String, replace: Boolean, snapshot: Menu?) {
        val selected = _placement.value ?: return
        val user = signedInUser()?.takeIf { it.canWrite } ?: return
        val today = listOfNotNull(_menu.value.menu?.today, snapshot?.today).maxOrNull() ?: return
        if (date < today || (meal != "lunch" && meal != "dinner") || _menu.value.working != null) return
        val draft = _menu.value.proposal
        val done = "Ajouté au menu : ${app.mijote.ui.mealMoment(date, meal)}"
        val completed = {
            if (_placement.value == selected) {
                _placement.value = null
                _recipe.value = null
                _tab.value = Tab.MENUS
            }
        }
        if (draft != null) {
            if (date !in draft.from..draft.to || _menu.value.proposalConflict) return
            _menu.update { it.copy(working = "plan-recipe", error = null) }
            viewModelScope.launch {
                try {
                    val detail = session.authed { api, access -> api.recipe(access, selected.id) }
                    if (detail.archived) {
                        _menu.update { it.copy(working = null, error = "Cette recette est archivée.") }
                        return@launch
                    }
                    val existing = draft.slots.find { it.date == date && it.meal == meal }
                    val recipe = MenuRecipe(detail.id, detail.title, detail.dishType, detail.totalMinutes ?: detail.prepMinutes, detail.image)
                    val slot = MenuSlot(existing?.id ?: UUID.randomUUID().toString(), date, meal, recipe, true,
                        people = existing?.people ?: user.people)
                    _menu.update { it.copy(proposal = draft.withSlot(slot), working = null) }
                    completed()
                } catch (e: SessionExpiredException) {
                    toLogin("Votre session a expiré : reconnectez-vous.")
                } catch (e: IOException) {
                    _menu.update { it.copy(working = null, error = errorMessage(e)) }
                }
            }
            return
        }
        if (date > today.plusDays(20)) return
        if (snapshot == null || snapshot != _plannerWeek.value.snapshot(date)) {
            _menu.update { it.copy(error = "La semaine a changé. Choisissez à nouveau le créneau.") }
            return
        }
        if (!replace) {
            change("$date|$meal", done, completed, date = date) { api, access ->
                api.planSlot(access, date, meal, selected.id, null, replace = false)
            }
            return
        }
        // A version conflict must not overwrite a meal different from the one shown at confirmation.
        val known = snapshot.slots.filter { it.date == date }
        val existing = known.find { it.meal == meal } ?: return
        val recipe = MenuRecipe(selected.id, selected.title, "", null)
        val patched = existing.copy(recipe = recipe, title = null, locked = true, people = user.people)
        val proposal = MenuProposal(date, date, known.map { if (it.id == existing.id) patched else it },
            known.map { SlotVersion(it.id, it.version) })
        change("$date|$meal", done, completed, date = date) { api, access ->
            api.confirmMenu(access, proposal)
        }
    }

    private fun validDraftTitle(title: String): Boolean {
        if (title.trim().codePointCount(0, title.trim().length) in 1..100) return true
        _menu.update { it.copy(error = "L’intitulé doit contenir entre 1 et 100 caractères.") }
        return false
    }

    fun renameMeal(slotId: String, title: String) {
        if (_menu.value.proposal != null) {
            if (validDraftTitle(title)) draftSlot(slotId) { it.copy(title = title.trim(), recipe = null, locked = true) }
        } else change(slotId) { api, access -> api.updateSlot(access, slotId, null, title.trim()) }
    }

    private fun notice(text: String) {
        Toast.makeText(getApplication(), text, Toast.LENGTH_SHORT).show()
    }

    fun selectTab(t: Tab) {
        _tab.value = t
        if (t == Tab.RECIPES && _recipes.value.recipes == null) searchRecipes(_recipes.value.query)
    }

    fun setRecipeFilters(f: RecipeFilters) {
        _recipes.update { it.copy(filters = f) }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    /** Searches the recipes by title or tag; typing restarts the search after a short pause. */
    fun searchRecipes(query: String, debounce: Boolean = false, manual: Boolean = false) {
        _recipes.update { it.copy(query = query, loading = true, refreshing = manual, error = null) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) kotlinx.coroutines.delay(300)
            try {
                val list = session.authed { api, access -> api.recipes(access, query) }
                _recipes.update { if (it.query == query) it.copy(recipes = list, loading = false, refreshing = false) else it }
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _recipes.update { it.copy(loading = false, refreshing = false, error = if (manual || it.recipes == null) errorMessage(e) else it.error) }
            }
        }
    }

    /** Opens the editor, empty or prefilled with the recipe shown. */
    fun editRecipe(new: Boolean) {
        val r = _recipe.value?.recipe
        _editor.value = if (new || r == null) EditorState(null, RecipeDraft()) else EditorState(r.id, RecipeDraft.of(r))
    }

    fun updateDraft(d: RecipeDraft) {
        _editor.update { it?.copy(draft = d, error = null) }
    }

    fun closeEditor() {
        if (_editor.value?.saving != true) _editor.value = null
    }

    fun saveRecipe() {
        val e = _editor.value ?: return
        if (e.saving) return
        e.draft.problem()?.let { p -> _editor.update { it?.copy(error = p) }; return }
        _editor.update { it?.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = session.authed { api, access ->
                    if (e.id == null) api.createRecipe(access, e.draft) else api.updateRecipe(access, e.id, e.draft)
                }
                _editor.value = null
                _recipe.value = RecipeState(saved.id, saved.title, saved)
                notice(if (e.id == null) "Recette ajoutée." else "Recette enregistrée.")
                searchRecipes(_recipes.value.query)
                if (e.id != null) loadMenu() // the menus show the new title
            } catch (ex: SessionExpiredException) {
                _editor.value = null
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (ex: IOException) {
                _editor.update { it?.copy(saving = false, error = errorMessage(ex)) }
            }
        }
    }

    /**
     * Adds a recipe to the user's own favorites or removes it: the heart changes at once (detail
     * and list) and comes back if the server refuses.
     */
    fun toggleFavorite(id: String) {
        val now = _recipe.value?.recipe?.takeIf { it.id == id }?.favorite
            ?: _recipes.value.recipes?.firstOrNull { it.id == id }?.favorite ?: return
        fun apply(fav: Boolean) {
            _recipe.update { r -> if (r?.recipe?.id == id) r.copy(recipe = r.recipe.copy(favorite = fav)) else r }
            _recipes.update { s -> s.copy(recipes = s.recipes?.map { if (it.id == id) it.copy(favorite = fav) else it }) }
        }
        apply(!now)
        viewModelScope.launch {
            try {
                session.authed { api, access -> api.setFavorite(access, id, !now) }
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                apply(now)
                notice(errorMessage(e))
            }
        }
    }

    /** Archives the recipe shown (admin): it leaves the list but stays in past menus and can be restored from the web admin. */
    fun archiveRecipe() {
        val r = _recipe.value ?: return
        if (r.busy) return
        _recipe.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                session.authed { api, access -> api.archiveRecipe(access, r.id) }
                _recipe.value = null
                notice("Recette archivée.")
                searchRecipes(_recipes.value.query)
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _recipe.update { it?.copy(busy = false, error = errorMessage(e)) }
            }
        }
    }

    // ---------- Administration ----------

    fun openAdmin(section: AdminSection) {
        val user = (_state.value.screen as? Screen.Preferences)?.user ?: (_state.value.screen as? Screen.Admin)?.user ?: return
        if (!user.isAdmin) return
        _admin.update { it.copy(created = null, error = null) }
        _state.value = UiState(Screen.Admin(user, section))
        loadAdmin(section)
    }

    fun closeAdmin() {
        val a = _state.value.screen as? Screen.Admin ?: return
        _admin.update { it.copy(created = null, error = null) }
        _state.value = UiState(Screen.Preferences(a.user))
    }

    private fun loadAdmin(section: AdminSection) = adminAction {
        when (section) {
            AdminSection.INVITATIONS -> {
                val inv = session.authed { api, access -> api.invitations(access) }
                _admin.update { it.copy(invitations = inv) }
            }
            AdminSection.ACCOUNTS -> {
                val list = session.authed { api, access -> api.accounts(access) }
                _admin.update { it.copy(accounts = list) }
            }
        }
    }

    private fun adminAction(action: suspend () -> Unit) {
        _admin.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                action()
                _admin.update { it.copy(busy = false) }
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _admin.update { it.copy(busy = false, error = errorMessage(e)) }
            }
        }
    }

    fun createInvitation(role: String, days: Int) = adminAction {
        val created = session.authed { api, access -> api.createInvitation(access, role, days) }
        val inv = session.authed { api, access -> api.invitations(access) }
        _admin.update { it.copy(created = created, invitations = inv) }
    }

    fun dismissCreatedInvitation() {
        _admin.update { it.copy(created = null) }
    }

    fun revokeInvitation(id: String) = adminAction {
        session.authed { api, access -> api.revokeInvitation(access, id) }
        val inv = session.authed { api, access -> api.invitations(access) }
        _admin.update { it.copy(invitations = inv) }
    }

    fun updateAccount(id: String, role: String? = null, disabled: Boolean? = null) = adminAction {
        val u = session.authed { api, access -> api.updateAccount(access, id, role, disabled) }
        _admin.update { s -> s.copy(accounts = s.accounts?.map { if (it.id == u.id) u else it }) }
    }

    fun resetPassword(id: String, password: String) {
        if (password.length < 8) {
            _admin.update { it.copy(error = "Le mot de passe doit faire au moins 8 caractères.") }
            return
        }
        adminAction {
            session.authed { api, access -> api.resetPassword(access, id, password) }
            notice("Mot de passe changé : ses appareils sont déconnectés.")
        }
    }

    /** Opens a recipe; [keep] reloads the one shown without clearing it first. */
    fun openRecipe(id: String, title: String, keep: Boolean = false, slotId: String? = null, slotPeople: Int? = null, slotReadOnly: Boolean = false) {
        if (!keep || _recipe.value?.id != id) _recipe.value = RecipeState(id, title, slotId = slotId, slotPeople = slotPeople, slotReadOnly = slotReadOnly)
        viewModelScope.launch {
            try {
                val r = session.authed { api, access -> api.recipe(access, id) }
                _recipe.update { if (it?.id == id) it.copy(recipe = r) else it }
                if (!r.archived && (_state.value.screen as? Screen.Home)?.user?.isReadonly == true) loadWishStatus(id)
            } catch (e: SessionExpiredException) {
                _recipe.value = null
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                // A background reload keeps the recipe shown (offline reading); an archived one is closed.
                if (keep && e is ApiException && e.status == 404) _recipe.update { if (it?.id == id) null else it }
                else if (!keep) _recipe.update { if (it?.id == id) it.copy(error = errorMessage(e)) else it }
            }
        }
    }

    private fun signedInUser(): User? = when (val screen = _state.value.screen) {
        is Screen.Home -> screen.user
        is Screen.Preferences -> screen.user
        is Screen.Admin -> screen.user
        else -> null
    }

    private fun loadWishStatus(id: String) {
        val actorId = signedInUser()?.id ?: return
        val revision = _recipe.value?.takeIf { it.id == id }?.wishRevision ?: return
        viewModelScope.launch {
            try {
                val status = session.authed { api, access -> api.wishStatus(access, id) }
                if (signedInUser()?.id != actorId) return@launch
                _recipe.update { if (it?.id == id && it.wishRevision == revision) it.copy(wish = status, wishError = null) else it }
            } catch (e: SessionExpiredException) {
                if (signedInUser()?.id != actorId) return@launch
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                if (signedInUser()?.id != actorId) return@launch
                _recipe.update { if (it?.id == id && it.wishRevision == revision) it.copy(wishError = errorMessage(e)) else it }
            }
        }
    }

    fun requestRecipe() {
        val actorId = signedInUser()?.id ?: return
        val recipe = _recipe.value ?: return
        if (signedInUser()?.isReadonly != true || recipe.wishing || recipe.wish?.requested == true) return
        _recipe.update { it?.copy(wishing = true, wishError = null, wishRevision = it.wishRevision + 1) }
        viewModelScope.launch {
            try {
                val status = session.authed { api, access -> api.requestRecipe(access, recipe.id) }
                if (signedInUser()?.id != actorId) return@launch
                _recipe.update { if (it?.id == recipe.id) it.copy(wishing = false, wish = status) else it }
            } catch (e: SessionExpiredException) {
                if (signedInUser()?.id != actorId) return@launch
                _recipe.update { it?.copy(wishing = false) }; toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                if (signedInUser()?.id != actorId) return@launch
                _recipe.update { if (it?.id == recipe.id) it.copy(wishing = false, wishError = errorMessage(e)) else it }
                loadWishStatus(recipe.id)
            }
        }
    }

    fun loadWishSettings() {
        val actorId = signedInUser()?.id ?: return
        if (signedInUser()?.canWrite != true || _wishSettings.value.busy) return
        _wishSettings.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val limit = session.authed { api, access -> api.wishLimit(access) }
                if (signedInUser()?.id != actorId) return@launch
                _wishSettings.value = WishSettingsState(limit)
            } catch (e: SessionExpiredException) {
                if (signedInUser()?.id != actorId) return@launch
                _wishSettings.update { it.copy(busy = false) }; toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                if (signedInUser()?.id != actorId) return@launch
                _wishSettings.update { it.copy(busy = false, error = errorMessage(e)) }
            }
        }
    }

    fun setWishLimit(limit: Int) {
        val actorId = signedInUser()?.id ?: return
        if (signedInUser()?.canWrite != true || _wishSettings.value.busy) return
        _wishSettings.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = session.authed { api, access -> api.setWishLimit(access, limit) }
                if (signedInUser()?.id != actorId) return@launch
                _wishSettings.value = WishSettingsState(saved)
            } catch (e: SessionExpiredException) {
                if (signedInUser()?.id != actorId) return@launch
                _wishSettings.update { it.copy(busy = false) }; toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                if (signedInUser()?.id != actorId) return@launch
                _wishSettings.update { it.copy(busy = false, error = errorMessage(e)) }
            }
        }
    }

    fun openNotifications() {
        val user = signedInUser()?.takeIf { it.canWrite } ?: return
        notificationsFromPreferences = _state.value.screen is Screen.Preferences
        _state.value = UiState(Screen.Home(user))
        _notifications.update { it.copy(open = true) }
        loadNotifications()
    }
    fun closeNotifications() {
        _notifications.update { it.copy(open = false) }
        if (notificationsFromPreferences) signedInUser()?.let { _state.value = UiState(Screen.Preferences(it)) }
        notificationsFromPreferences = false
    }
    fun loadNotifications(more: Boolean = false) {
        val actorId = signedInUser()?.id ?: return
        if (signedInUser()?.canWrite != true) { _notifications.value = NotificationsState(); return }
        if (_notifications.value.loading || _notifications.value.working != null) return
        val offset = if (more) _notifications.value.items.size else 0
        _notifications.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val page = session.authed { api, access -> api.notifications(access, offset) }
                if (signedInUser()?.id != actorId) return@launch
                _notifications.update { it.copy(items = if (more) (it.items + page.items).distinctBy { n -> n.id } else page.items,
                    unreadCount = page.unreadCount, hasMore = page.hasMore, loading = false) }
            } catch (e: SessionExpiredException) {
                if (signedInUser()?.id != actorId) return@launch
                _notifications.update { it.copy(loading = false) }; toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                if (signedInUser()?.id != actorId) return@launch
                if (e is ApiException && e.status == 403) _notifications.value = NotificationsState(error = errorMessage(e))
                else _notifications.update { it.copy(loading = false, error = errorMessage(e)) }
            }
        }
    }
    fun readNotification(item: RecipeNotification) = changeNotification(item, delete = false)
    fun deleteNotification(item: RecipeNotification) = changeNotification(item, delete = true)
    private fun changeNotification(item: RecipeNotification, delete: Boolean) {
        val actorId = signedInUser()?.id ?: return
        if (_notifications.value.loading || _notifications.value.working != null || (!delete && item.readAt != null)) return
        _notifications.update { it.copy(working = item.id, error = null) }
        viewModelScope.launch {
            try {
                session.authed { api, access -> if (delete) api.deleteNotification(access, item.id) else api.readNotification(access, item.id) }
                if (signedInUser()?.id != actorId) return@launch
                _notifications.update { state -> state.copy(working = null,
                    items = if (delete) state.items.filterNot { it.id == item.id } else state.items.map { if (it.id == item.id) it.copy(readAt = java.time.Instant.now()) else it },
                    unreadCount = (state.unreadCount - if (item.readAt == null) 1 else 0).coerceAtLeast(0)) }
            } catch (e: SessionExpiredException) {
                if (signedInUser()?.id != actorId) return@launch
                _notifications.update { it.copy(working = null) }; toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                if (signedInUser()?.id != actorId) return@launch
                _notifications.update { it.copy(working = null, error = errorMessage(e)) }
            }
        }
    }
    fun openNotification(item: RecipeNotification) {
        readNotification(item)
        if (item.recipeAvailable) openRecipe(item.recipeId, item.recipeTitle)
    }

    fun closeRecipe() {
        _recipe.value = null
    }

    private fun resetPlanner() {
        weekJob?.cancel()
        beforeWeekJob?.cancel()
        afterWeekJob?.cancel()
        mealEditJob?.cancel()
        copyJob?.cancel()
        pickerJob?.cancel()
        preferenceJob?.cancel()
        searchJob?.cancel()
        peopleJob?.cancel()
        cachedWeeks.clear()
        visiblePlannerWeek = null
        _plannerWeek.value = PlannerWeekState(jumpSerial = _plannerWeek.value.jumpSerial + 1)
        _plannerRecipes.value = RecipesState()
        _placement.value = null
        _weekCopy.value = WeekCopyState()
        generationRequest = null
    }

    /** Forgets what the signed-out account had loaded. */
    private fun resetData() {
        resetPlanner()
        _notifications.value = NotificationsState()
        _wishSettings.value = WishSettingsState()
        notificationsFromPreferences = false
        _menu.value = MenuState()
        _recipes.value = RecipesState()
        _recipe.value = null
        _editor.value = null
        _admin.value = AdminState()
        _tab.value = Tab.MENUS
    }

    fun logout() {
        viewModelScope.launch {
            session.logout()
            toLogin()
        }
    }

    fun installUpdate() {
        val release = (_update.value as? UpdateState.Available)?.release ?: return
        _update.value = UpdateState.Downloading(release)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { updater.downloadAndInstall(release) }
                _update.value = UpdateState.None
            } catch (e: IOException) {
                _update.value = UpdateState.Available(release, e.message ?: "Téléchargement impossible.")
            }
        }
    }

    fun dismissUpdate() {
        if (_update.value !is UpdateState.Downloading) _update.value = UpdateState.None
    }
}

internal fun errorMessage(e: IOException): String = when {
    e !is ApiException -> "Serveur injoignable : vérifiez la connexion et l'adresse."
    e.code == "invalid_invitation" -> "Cette invitation est invalide, déjà utilisée ou expirée : demandez-en une nouvelle."
    e.code == "wish_limit_reached" || e.code == "no_menu_creators" -> e.message ?: "Demande indisponible pour le moment."
    e.code == "invalid_response" -> e.message ?: "La réponse du serveur est illisible."
    e.code == "validation_error" -> e.message ?: "Données refusées par le serveur."
    e.code == "menu_changed" -> "Le menu enregistré a changé. Cette validation n’a pas appliqué la proposition. Actualisez le menu ou recalculez la proposition."
    e.code == "not_found" -> "Introuvable : cet élément a peut-être été supprimé entre-temps."
    e.code == "not_mijote" || e.status == 404 -> "Cette adresse ne répond pas comme un serveur Mijote."
    e.code == "invalid_credentials" -> "Identifiant ou mot de passe incorrect."
    e.code == "too_many_requests" -> "Trop de tentatives : réessayez dans quelques minutes."
    e.code == "slot_exists" -> "Ce repas est déjà prévu ce jour-là."
    e.code == "not_enough_recipes" -> "Pas assez de recettes pour générer un menu : ajoutez-en d'abord."
    e.code == "forbidden" -> "Action non autorisée pour votre compte."
    e.status == 409 -> "Ce contenu existe déjà (par exemple une recette avec le même lien)."
    else -> "Erreur du serveur (${e.status})."

}
