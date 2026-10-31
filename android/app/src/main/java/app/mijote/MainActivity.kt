package app.mijote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mijote.ui.HomeScreen
import app.mijote.ui.InviteScreen
import app.mijote.ui.LoginScreen
import app.mijote.ui.MijoteTheme
import app.mijote.ui.ServerScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { vm.openInvitationLink(it.toString()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A link that launched the app (not a re-creation after rotation).
        if (savedInstanceState == null) intent?.data?.let { vm.openInvitationLink(it.toString()) }
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            MijoteTheme {
                when (val s = state.screen) {
                    Screen.Server -> ServerScreen(state.busy, state.error, onSubmit = vm::submitServer,
                        onInvite = vm::showInvite)
                    is Screen.Login -> LoginScreen(s.serverUrl, state.busy, state.error,
                        onLogin = vm::login, onChangeServer = vm::changeServer, onInvite = vm::showInvite)
                    is Screen.Invite -> InviteScreen(s.serverUrl, s.code, state.busy, state.error,
                        onSubmit = vm::redeemInvitation, onBack = vm::backFromInvite)
                    is Screen.Home -> HomeScreen(s.user, onLogout = vm::logout)
                }
            }
        }
    }
}
