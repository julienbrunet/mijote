package app.mijote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.mijote.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** First launch: the address of the family's server (FR-ONB-05). */
@Composable
fun ServerScreen(busy: Boolean, error: String?, onSubmit: (String) -> Unit, onInvite: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    OnboardingScaffold("Ça\nmijote", "Vos menus de la semaine, en famille") {
        SunnyCard {
            Text("Adresse du serveur", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink)
            Text("Celle de l'instance Mijote de votre famille.", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            SunnyField(url, { url = it }, "Adresse (ex. mesrepas.exemple.fr)", KeyboardType.Uri, ImeAction.Go,
                onDone = { onSubmit(url) }, enabled = !busy)
            ErrorText(error)
            PrimaryButton("Continuer", busy) { onSubmit(url) }
        }
        SecondaryAction("J'ai une invitation", !busy, onInvite)
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
    var passwordVisible by remember { mutableStateOf(false) }
    OnboardingScaffold("Bon\nretour", serverUrl.removePrefix("https://")) {
        SunnyCard {
            SunnyField(username, { username = it }, "Identifiant", enabled = !busy)
            Spacer(Modifier.height(4.dp))
            SunnyField(password, { password = it }, "Mot de passe", KeyboardType.Password, ImeAction.Go,
                onDone = { onLogin(username, password) },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), enabled = !busy,
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !busy) {
                        Icon(painterResource(if (passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            contentDescription = if (passwordVisible) "Masquer le mot de passe" else "Afficher le mot de passe",
                            tint = Sunny.InkSoft)
                    }
                })
            ErrorText(error)
            PrimaryButton("Se connecter", busy) { onLogin(username, password) }
        }
        SecondaryAction("Changer de serveur", !busy, onChangeServer)
        SecondaryAction("J'ai une invitation", !busy, onInvite)
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
    OnboardingScaffold("Rejoindre\nla famille", serverUrl?.removePrefix("https://") ?: "avec une invitation") {
        SunnyCard {
            if (serverUrl == null) {
                SunnyField(server, { server = it }, "Adresse du serveur", KeyboardType.Uri, enabled = !busy)
                Spacer(Modifier.height(4.dp))
            }
            if (code == null) {
                SunnyField(inviteCode, { inviteCode = it }, "Code d'invitation", enabled = !busy)
                Spacer(Modifier.height(4.dp))
            }
            SunnyField(username, { username = it }, "Identifiant", enabled = !busy)
            Spacer(Modifier.height(4.dp))
            SunnyField(displayName, { displayName = it }, "Nom affiché (facultatif)", enabled = !busy)
            Spacer(Modifier.height(4.dp))
            SunnyField(password, { password = it }, "Mot de passe (8+ caractères)", KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            Spacer(Modifier.height(4.dp))
            SunnyField(confirm, { confirm = it }, "Confirmation", KeyboardType.Password, ImeAction.Go,
                onDone = { submit() }, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            ErrorText(error)
            PrimaryButton("Créer mon compte", busy) { submit() }
        }
        SecondaryAction("J'ai déjà un compte", !busy, onBack)
    }
}
