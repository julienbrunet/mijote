package app.mijote

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mijote.data.ApiException
import app.mijote.data.AppRelease
import app.mijote.data.MealPlan
import app.mijote.data.Menu
import app.mijote.data.PrefsSessionStore
import app.mijote.data.Session
import app.mijote.data.SessionExpiredException
import app.mijote.data.Updater
import app.mijote.data.User
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
data class MenuState(
    val menu: Menu? = null,
    val generating: Boolean = false,
    val error: String? = null,
    val generated: Int = 0,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val session = Session(PrefsSessionStore(app))

    private val _state = MutableStateFlow(UiState(initialScreen()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val updater = Updater(app)
    private val _update = MutableStateFlow<UpdateState>(UpdateState.None)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    val currentVersion get() = updater.currentVersion

    private val _menu = MutableStateFlow(MenuState())
    val menu: StateFlow<MenuState> = _menu.asStateFlow()

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
        if (_state.value.screen is Screen.Home) return // already signed in: never replace the session by accident
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
    fun loadMenu() {
        viewModelScope.launch {
            try {
                val m = session.authed { api, access -> api.menu(access) }
                _menu.update { it.copy(menu = m) }
            } catch (e: SessionExpiredException) {
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (_: IOException) {
            }
        }
    }

    fun generateMenu(start: LocalDate, meals: Int, plan: MealPlan) {
        if (_menu.value.generating) return
        _menu.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            try {
                session.authed { api, access -> api.generateMenu(access, start, meals, plan) }
                val m = session.authed { api, access -> api.menu(access) }
                _menu.update { MenuState(menu = m, generated = it.generated + 1) }
            } catch (e: SessionExpiredException) {
                _menu.update { it.copy(generating = false) }
                toLogin("Votre session a expiré : reconnectez-vous.")
            } catch (e: IOException) {
                _menu.update { it.copy(generating = false, error = errorMessage(e)) }
            }
        }
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
    e.code == "validation_error" -> e.message ?: "Données refusées par le serveur."
    e.code == "not_mijote" || e.status == 404 -> "Cette adresse ne répond pas comme un serveur Mijote."
    e.code == "invalid_credentials" -> "Identifiant ou mot de passe incorrect."
    e.code == "too_many_requests" -> "Trop de tentatives : réessayez dans quelques minutes."
    e.code == "not_enough_recipes" -> "Pas assez de recettes pour générer un menu : ajoutez-en d'abord."
    else -> "Erreur du serveur (${e.status})."

}
