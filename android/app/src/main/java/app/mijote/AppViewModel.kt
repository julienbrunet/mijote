package app.mijote

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mijote.data.Api
import app.mijote.data.ApiException
import app.mijote.data.AppRelease
import app.mijote.data.MealPlan
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.mijote.data.Menu
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
import kotlinx.coroutines.Dispatchers
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

/** Period just generated, shown on the review screen (step 2 of the generation). */
data class ReviewPeriod(val from: LocalDate, val to: LocalDate)

data class MenuState(
    val menu: Menu? = null,
    val generating: Boolean = false,
    val error: String? = null,
    val generated: Int = 0,
    val review: ReviewPeriod? = null,
    /** Slot being changed (swap / remove) or "date|meal" of a meal being added: shows a spinner. */
    val working: String? = null,
    /** A pull-to-refresh is running. */
    val refreshing: Boolean = false,
)

/** Recipe detail screen: [recipe] is null while loading. */
data class RecipeState(
    val id: String, val title: String, val recipe: RecipeDetail? = null, val error: String? = null, val busy: Boolean = false,
    /** The planned meal the recipe was opened from: its number of people is shown and saved. */
    val slotId: String? = null,
    val slotPeople: Int? = null,
)

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
                    // Offline or server unreachable: stay signed in (read-only use).
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
        if (manual) {
            if (_menu.value.refreshing) return
            _menu.update { it.copy(refreshing = true, error = null) }
        }
        viewModelScope.launch {
            try {
                val m = session.authed { api, access -> api.menu(access) }
                _menu.update { it.copy(menu = m, refreshing = false) }
            } catch (e: SessionExpiredException) {
                _menu.update { it.copy(refreshing = false) }
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                // Automatic refreshes stay silent offline; a pull-to-refresh tells why nothing changed.
                _menu.update { it.copy(refreshing = false, error = if (manual) errorMessage(e) else it.error) }
            }
        }
    }

    /**
     * The app comes back to the foreground: what other members changed meanwhile is fetched
     * (FR-SHR-02, no real-time). Nothing runs while the app is in the background.
     */
    fun onAppForeground() {
        if (_state.value.screen !is Screen.Home) return
        loadMenu()
        if (_recipes.value.recipes != null) refreshRecipes(manual = false)
        _recipe.value?.let { r -> if (r.recipe != null && _editor.value == null) openRecipe(r.id, r.title, keep = true) }
    }

    /** Reloads the recipe list with the current search (pull-to-refresh or return to the app). */
    fun refreshRecipes(manual: Boolean = true) = searchRecipes(_recipes.value.query, manual = manual)

    fun openPreferences() {
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
    /** From the preferences or the "Idées de repas" screen. */
    fun setPeople(people: Int) {
        val screen = _state.value.screen
        val before = when (screen) {
            is Screen.Preferences -> screen.user
            is Screen.Home -> screen.user
            else -> return
        }
        fun withUser(s: Screen, u: User) = when (s) {
            is Screen.Preferences -> Screen.Preferences(u)
            is Screen.Home -> Screen.Home(u)
            else -> s
        }
        _state.update { it.copy(screen = withUser(it.screen, before.copy(people = people))) }
        viewModelScope.launch {
            try {
                val u = session.authed { api, access -> api.setPeople(access, people) }
                session.updateUser(u)
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _state.update { it.copy(screen = withUser(it.screen, before), error = errorMessage(e)) }
                _menu.update { it.copy(error = errorMessage(e)) }
            }
        }
    }

    fun generateMenu(start: LocalDate, meals: Int, plan: MealPlan) {
        if (_menu.value.generating) return
        _menu.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            try {
                val slots = session.authed { api, access -> api.generateMenu(access, start, meals, plan) }
                val m = session.authed { api, access -> api.menu(access) }
                val end = slots.maxOfOrNull { it.date } ?: start
                _menu.update { MenuState(menu = m, generated = it.generated + 1, review = ReviewPeriod(start, end)) }
                _tab.value = Tab.MENUS
            } catch (e: SessionExpiredException) {
                _menu.update { it.copy(generating = false) }
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _menu.update { it.copy(generating = false, error = errorMessage(e)) }
            }
        }
    }

    /** Validates the generated menu: it is already saved, the review just closes. */
    fun closeReview() {
        _menu.update { it.copy(review = null, error = null) }
    }

    /** Runs a change of the planned meals, then reloads the menu; [done] is told on success. */
    private fun change(key: String, done: String? = null, action: (Api, String) -> Unit) {
        if (_menu.value.working != null) return
        _menu.update { it.copy(working = key, error = null) }
        viewModelScope.launch {
            try {
                session.authed { api, access -> action(api, access) }
                val m = session.authed { api, access -> api.menu(access) }
                _menu.update { it.copy(menu = m, working = null) }
                done?.let { notice(it) }
            } catch (e: SessionExpiredException) {
                _menu.update { it.copy(working = null) }
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _menu.update { it.copy(working = null, error = errorMessage(e)) }
            }
        }
    }

    /** Another recipe for a planned meal. */
    fun swapMeal(slotId: String) = change(slotId) { api, access -> api.swapSlot(access, slotId) }

    /** One more meal (lunch or dinner) on a day. */
    fun addMeal(date: LocalDate, meal: String) = change("$date|$meal") { api, access -> api.addSlot(access, date, meal) }

    fun removeMeal(slotId: String) = change(slotId) { api, access -> api.removeSlot(access, slotId) }

    /** Number of people of a planned meal, for the whole family (review screen). */
    fun setMealPeople(slotId: String, people: Int) = change(slotId) { api, access -> api.setSlotPeople(access, slotId, people) }

    private var peopleJob: kotlinx.coroutines.Job? = null

    /**
     * The stepper of a recipe opened from a planned meal: the meal's number of people changes at
     * once on screen and is saved after a short pause (several taps make one request).
     */
    fun saveMealPeople(slotId: String, people: Int) {
        _recipe.update { if (it?.slotId == slotId) it.copy(slotPeople = people) else it }
        _menu.update { s -> s.copy(menu = s.menu?.let { m -> m.copy(slots = m.slots.map { if (it.id == slotId) it.copy(people = people) else it }) }) }
        peopleJob?.cancel()
        peopleJob = viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            try {
                session.authed { api, access -> api.setSlotPeople(access, slotId, people) }
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                notice(errorMessage(e))
                loadMenu()
            }
        }
    }

    /**
     * Plans a recipe ([recipeId]) or a manual meal ([title]) on a day, from the recipe screens or the
     * menus; [replace] overwrites the meal already planned at that time (the dialog warned about it).
     */
    fun planMeal(date: LocalDate, meal: String, recipeId: String?, title: String?, replace: Boolean) =
        change("$date|$meal", "Ajouté au menu : ${app.mijote.ui.mealMoment(date, meal)}") { api, access ->
            api.planSlot(access, date, meal, recipeId, title?.trim(), replace)
        }

    /** Changes a planned meal into a manual meal with this title (or renames a manual meal). */
    fun renameMeal(slotId: String, title: String) = change(slotId) { api, access -> api.updateSlot(access, slotId, null, title.trim()) }

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
    fun openRecipe(id: String, title: String, keep: Boolean = false, slotId: String? = null, slotPeople: Int? = null) {
        if (!keep || _recipe.value?.id != id) _recipe.value = RecipeState(id, title, slotId = slotId, slotPeople = slotPeople)
        viewModelScope.launch {
            try {
                val r = session.authed { api, access -> api.recipe(access, id) }
                _recipe.update { if (it?.id == id) it.copy(recipe = r) else it }
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

    fun closeRecipe() {
        _recipe.value = null
    }

    /** Forgets what the signed-out account had loaded. */
    private fun resetData() {
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
            resetData()
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
    e.code == "validation_error" -> e.message ?: "Données refusées par le serveur."
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
