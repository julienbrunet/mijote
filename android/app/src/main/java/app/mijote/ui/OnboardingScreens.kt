package app.mijote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** First launch: the address of the family's server (FR-ONB-05). */
@Composable
fun ServerScreen(busy: Boolean, error: String?, onSubmit: (String) -> Unit, onInvite: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    OnboardingScaffold {
        ScreenTitle("Ça mijote", "Vos menus de la semaine, en famille")
        Spacer(Modifier.height(32.dp))
        CalmCard {
            Text("Adresse du serveur", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("Celle de l'instance Mijote de votre famille.", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            CalmField(url, { url = it }, "Adresse (ex. mesrepas.exemple.fr)", KeyboardType.Uri, ImeAction.Go,
                onDone = { onSubmit(url) }, enabled = !busy)
            ErrorText(error)
            PrimaryButton("Continuer", busy) { onSubmit(url) }
        }
        TextButton(onClick = onInvite, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
            Text("J'ai une invitation")
        }
    }
}

/** Sign-in with username and password (FR-ONB-06). */
@Composable
fun LoginScreen(
    serverUrl: String,
    busy: Boolean,
    error: String?,
    onLogin: (String, String) -> Unit,
    onChangeServer: () -> Unit,
    onInvite: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    OnboardingScaffold {
        ScreenTitle("Connexion", serverUrl.removePrefix("https://"))
        Spacer(Modifier.height(32.dp))
        CalmCard {
            CalmField(username, { username = it }, "Identifiant", enabled = !busy)
            Spacer(Modifier.height(12.dp))
            CalmField(password, { password = it }, "Mot de passe", KeyboardType.Password, ImeAction.Go,
                onDone = { onLogin(username, password) },
                visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            ErrorText(error)
            PrimaryButton("Se connecter", busy) { onLogin(username, password) }
        }
        TextButton(onClick = onChangeServer, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
            Text("Changer de serveur")
        }
        TextButton(onClick = onInvite, enabled = !busy) { Text("J'ai une invitation") }
    }
}

/**
 * Joins the family with an invitation (FR-ONB-03). Opened from an invitation link the
 * server and the code are already known; otherwise they are typed by hand.
 */
@Composable
fun InviteScreen(
    serverUrl: String?,
    code: String?,
    busy: Boolean,
    error: String?,
    onSubmit: (server: String, code: String, username: String, displayName: String, password: String, confirm: String) -> Unit,
    onBack: () -> Unit,
) {
    var server by rememberSaveable { mutableStateOf(serverUrl.orEmpty()) }
    var inviteCode by rememberSaveable { mutableStateOf(code.orEmpty()) }
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val submit = { onSubmit(server, inviteCode, username, displayName, password, confirm) }
    OnboardingScaffold {
        ScreenTitle("Rejoindre", serverUrl?.removePrefix("https://") ?: "avec une invitation")
        Spacer(Modifier.height(32.dp))
        CalmCard {
            if (serverUrl == null) {
                CalmField(server, { server = it }, "Adresse du serveur", KeyboardType.Uri, enabled = !busy)
                Spacer(Modifier.height(12.dp))
            }
            if (code == null) {
                CalmField(inviteCode, { inviteCode = it }, "Code d'invitation", enabled = !busy)
                Spacer(Modifier.height(12.dp))
            }
            CalmField(username, { username = it }, "Identifiant", enabled = !busy)
            Spacer(Modifier.height(12.dp))
            CalmField(displayName, { displayName = it }, "Nom affiché (facultatif)", enabled = !busy)
            Spacer(Modifier.height(12.dp))
            CalmField(password, { password = it }, "Mot de passe (8+ caractères)", KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            Spacer(Modifier.height(12.dp))
            CalmField(confirm, { confirm = it }, "Confirmation", KeyboardType.Password, ImeAction.Go,
                onDone = { submit() }, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            ErrorText(error)
            PrimaryButton("Créer mon compte", busy) { submit() }
        }
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
            Text("J'ai déjà un compte")
        }
    }
}
