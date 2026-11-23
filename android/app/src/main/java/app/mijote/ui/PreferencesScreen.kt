package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.WishSettingsState
import app.mijote.data.User

private val preferenceControlShape = RoundedCornerShape(12.dp)
private const val MAX_PEOPLE = 20
private const val MAX_WISHES = 100

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
    onBack: (() -> Unit)? = null,
    onInvitations: () -> Unit = {},
    onAccounts: () -> Unit = {},
    notificationCount: Int = 0,
    onNotifications: () -> Unit = {},
    wishSettings: WishSettingsState = WishSettingsState(),
    onWishLimit: (Int) -> Unit = {},
    onReloadWishSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            containerColor = Sunny.Card,
            shape = Shapes.Control,
            title = { Text("Se déconnecter ?", color = Sunny.Ink, fontWeight = FontWeight.Bold) },
            text = { Text("L’adresse du serveur est conservée. Vous pourrez vous reconnecter avec votre compte.", color = Sunny.Ink) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text("Se déconnecter", color = Sunny.Ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Annuler", color = Sunny.Ink) }
            },
        )
    }

    Column(modifier.fillMaxSize().background(Sunny.Cream).statusBarsPadding()) {
        if (onBack != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            AgendaAction("Retour", onBack, icon = Icons.AutoMirrored.Filled.ArrowBack)
        }
        AgendaHeader("Compte")

        Column(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (onBack != null) Modifier.navigationBarsPadding() else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PreferenceCard {
                val roleLabel = when {
                    user.isAdmin -> "Administrateur"
                    user.isReadonly -> "Lecture seule"
                    else -> "Membre"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(Sunny.ButterLight),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Person, contentDescription = null, tint = Sunny.Ink) }
                    Column(Modifier.weight(1f)) {
                        Text(user.displayName, fontSize = 18.sp, lineHeight = 23.sp,
                            fontWeight = FontWeight.Bold, color = Sunny.Ink)
                        PreferenceHint("@${user.username}")
                    }
                    Text(
                        roleLabel,
                        Modifier.widthIn(max = 132.dp).clip(preferenceControlShape)
                            .background(Sunny.ButterLight).padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = Sunny.Ink, textAlign = TextAlign.Center,
                    )
                }
            }

            if (user.canWrite) {
                PreferenceCard("Repas") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            PreferenceLabel("Personnes par défaut")
                            PreferenceHint("Pour les repas générés")
                        }
                        PreferenceStepper(user.people, MAX_PEOPLE, "Une personne", onValue = onPeople)
                    }
                }

                PreferenceCard("Envies de recettes") {
                    PreferenceEntry(
                        "Notifications",
                        if (notificationCount > 0) "$notificationCount non lue${if (notificationCount > 1) "s" else ""}"
                            else "Demandes des comptes en lecture seule",
                        Icons.Filled.Notifications, onNotifications,
                    )
                    HorizontalDivider(color = Sunny.Track)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            PreferenceLabel("Limite hebdomadaire")
                            PreferenceHint("Par compte en lecture seule, pour tout le foyer")
                        }
                        wishSettings.limit?.let { limit ->
                            PreferenceStepper(limit, MAX_WISHES, "Une demande", !wishSettings.busy, onWishLimit)
                        }
                    }
                    if (wishSettings.busy && wishSettings.limit == null) PreferenceHint("Chargement…")
                    ErrorText(wishSettings.error)
                    if (wishSettings.error != null) {
                        AgendaAction("Réessayer", onReloadWishSettings, enabled = !wishSettings.busy)
                    }
                }
            }

            if (user.isAdmin) PreferenceCard("Administration") {
                PreferenceEntry("Invitations", "Créer et partager une invitation", Icons.Filled.Person, onInvitations)
                HorizontalDivider(color = Sunny.Track)
                PreferenceEntry("Comptes", "Rôles, accès et mots de passe", Icons.Filled.Settings, onAccounts)
            }

            PreferenceCard("Serveur") {
                PreferenceHint(serverUrl.removePrefix("https://"))
                AgendaAction("Changer de serveur", onChangeServer)
            }
            PreferenceCard("Application") {
                PreferenceHint("Version $appVersion")
                updateMessage?.let { PreferenceHint(it) }
                AgendaAction(
                    if (checkingUpdate) "Recherche…" else "Rechercher des mises à jour",
                    onCheckUpdate, enabled = !checkingUpdate,
                )
            }

            AgendaAction("Se déconnecter", { confirmLogout = true })
        }
    }
}

@Composable
private fun PreferenceCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Shapes.Control).background(Sunny.Card)
            .border(1.dp, Sunny.Track, Shapes.Control).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (title != null) Text(
            title, Modifier.semantics { heading() }, fontSize = 17.sp, lineHeight = 21.sp,
            fontWeight = FontWeight.Bold, color = Sunny.Ink,
        )
        content()
    }
}

@Composable
private fun PreferenceEntry(title: String, hint: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(preferenceControlShape).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(preferenceControlShape).background(Sunny.ButterLight),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = Sunny.Ink) }
        Column(Modifier.weight(1f)) {
            PreferenceLabel(title)
            PreferenceHint(hint)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            modifier = Modifier.size(20.dp), tint = Sunny.InkTitle,
        )
    }
}

@Composable
private fun PreferenceLabel(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
}

@Composable
private fun PreferenceHint(text: String) {
    Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = Sunny.InkTitle)
}

@Composable
private fun PreferenceStepper(
    value: Int,
    maximum: Int,
    unit: String,
    enabled: Boolean = true,
    onValue: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepTile("−", "$unit de moins", enabled && value > 1) { onValue(value - 1) }
        Text(
            value.toString(), Modifier.widthIn(min = 26.dp), color = Sunny.Ink,
            fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        StepTile("+", "$unit de plus", enabled && value < maximum) { onValue(value + 1) }
    }
}

@Composable
private fun StepTile(sign: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(preferenceControlShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).clip(preferenceControlShape)
                .background(if (enabled) Sunny.ButterLight else Sunny.Cream),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                sign, Modifier.clearAndSetSemantics {}, fontSize = 21.sp, lineHeight = 23.sp,
                color = if (enabled) Sunny.Ink else Sunny.InkTitle,
            )
        }
    }
}
