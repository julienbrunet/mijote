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
fun ServerScreen(busy: Boolean, error: String?, onSubmit: (String) -> Unit) {
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
    }
}
