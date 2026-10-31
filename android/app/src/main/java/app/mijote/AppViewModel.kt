package app.mijote

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mijote.data.ApiException
import app.mijote.data.PrefsSessionStore
import app.mijote.data.Session
import app.mijote.data.SessionExpiredException
import app.mijote.data.User
import app.mijote.data.normalizeServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** What the app shows: server address, sign-in, or the signed-in app. */
sealed interface Screen {
    data object Server : Screen
    data class Login(val serverUrl: String) : Screen
    data class Home(val user: User) : Screen
}

data class UiState(
    val screen: Screen,
    val busy: Boolean = false,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val session = Session(PrefsSessionStore(app))

    private val _state = MutableStateFlow(UiState(initialScreen()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Screens render from local state immediately; the session is checked in the background.
        if (_state.value.screen is Screen.Home) {
            viewModelScope.launch {
                try {
                    val u = session.refreshUser()
                    _state.update { if (it.screen is Screen.Home) it.copy(screen = Screen.Home(u)) else it }
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
        }
    }

    fun logout() {
        viewModelScope.launch {
            session.logout()
            toLogin()
        }
    }
}

internal fun errorMessage(e: IOException): String = when {
    e !is ApiException -> "Serveur injoignable : vérifiez la connexion et l'adresse."
    e.code == "not_mijote" || e.status == 404 -> "Cette adresse ne répond pas comme un serveur Mijote."
    e.code == "invalid_credentials" -> "Identifiant ou mot de passe incorrect."
    e.code == "too_many_requests" -> "Trop de tentatives : réessayez dans quelques minutes."
    else -> "Erreur du serveur (${e.status})."
}
