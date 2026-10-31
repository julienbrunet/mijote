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
import app.mijote.ui.menuDays
import app.mijote.ui.menuPeriod

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

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
            MijoteTheme {
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
                            onLogout = vm::logout, onBack = vm::closePreferences)
                    }
                    is Screen.Home -> {
                        val m = menu.menu
                        HomeScreen(
                            s.user, onLogout = vm::logout,
                            period = m?.let(::menuPeriod), days = m?.let(::menuDays).orEmpty(),
                            generatedCount = menu.generated, onNewWeek = vm::loadMenu, onPreferences = vm::openPreferences,
                        ) {
                            GenerateScreen(m?.info, m?.today ?: LocalDate.now(), s.user.people, menu.generating, menu.error,
                                onGenerate = vm::generateMenu)
                        }
                    }
                }
            }
        }
    }
}
