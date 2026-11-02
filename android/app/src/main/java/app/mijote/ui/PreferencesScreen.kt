package app.mijote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import app.mijote.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.data.User

private const val MAX_PEOPLE = 20

/**
 * Account preferences: who is signed in, the number of people the menus are cooked for,
 * the server the app talks to, and the app version with a manual update check.
 */
@Composable
fun PreferencesScreen(
    user: User,
    serverUrl: String,
    appVersion: String,
    checkingUpdate: Boolean,
    updateMessage: String?,
    onPeople: (Int) -> Unit,
    onCheckUpdate: () -> Unit,
    onChangeServer: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    onInvitations: () -> Unit = {},
    onAccounts: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(Sunny.Cream).verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth()
                .background(Sunny.Butter, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp)),
        ) {
            CornerBlock(Sunny.Card, size = 88.dp, modifier = Modifier.align(Alignment.TopEnd)) {
                Image(painterResource(R.drawable.ic_mijote_cocotte), contentDescription = null,
                    modifier = Modifier.statusBarsPadding().size(56.dp))
            }
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 100.dp, top = 12.dp, bottom = 64.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Sunny.Ink)
                }
                Box(Modifier.padding(start = 12.dp, top = 20.dp)) { ScreenTitle("Préférences", size = 40) }
            }
        }
        Column(
            Modifier.offset(y = (-40).dp).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SunnyCard {
                SectionLabel("COMPTE")
                Text(user.displayName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink)
                val roleLabel = when (user.role) {
                    "admin" -> "administrateur"
                    "readonly" -> "lecture seule"
                    else -> "membre"
                }
                Text("@${user.username} · $roleLabel",
                    fontSize = 13.sp, color = Sunny.InkSoft, modifier = Modifier.padding(top = 2.dp))
            }
            SunnyCard {
                SectionLabel("REPAS")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Nombre de personnes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
                        Text("Pour chaque repas généré", fontSize = 13.sp, color = Sunny.InkSoft)
                    }
                    // Stepper on the right, within reach of the thumb.
                    StepTile("−", "Une personne de moins", user.people > 1) { onPeople(user.people - 1) }
                    Text("${user.people}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink,
                        modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    StepTile("+", "Une personne de plus", user.people < MAX_PEOPLE) { onPeople(user.people + 1) }
                }
            }
            if (user.isAdmin) SunnyCard {
                SectionLabel("ADMINISTRATION")
                AdminEntry("Invitations", "Créer et partager une invitation", onInvitations)
                AdminEntry("Comptes", "Rôles, désactivation, mots de passe", onAccounts)
            }
            SunnyCard {
                SectionLabel("SERVEUR")
                Text(serverUrl.removePrefix("https://"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
                Spacer(Modifier.height(4.dp))
                SecondaryAction("Changer de serveur", enabled = true, onClick = onChangeServer)
            }
            SunnyCard {
                SectionLabel("APPLICATION")
                Text("Version $appVersion", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
                updateMessage?.let { Text(it, fontSize = 13.sp, color = Sunny.InkSoft, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(14.dp))
                PrimaryButton(if (checkingUpdate) "Recherche…" else "Rechercher des mises à jour", checkingUpdate, onCheckUpdate)
            }
            SecondaryAction("Se déconnecter", enabled = true, onClick = onLogout)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AdminEntry(title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Shapes.Control).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
            Text(hint, fontSize = 13.sp, color = Sunny.InkSoft)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Sunny.InkSoft)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkSoft,
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun StepTile(sign: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(Shapes.Control).background(if (enabled) Sunny.ButterLight else Sunny.Track)
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (sign == "+") Icon(Icons.Filled.Add, contentDescription = description, tint = Sunny.Ink)
        else Text(sign, fontSize = 22.sp, color = Sunny.Ink)
    }
}
