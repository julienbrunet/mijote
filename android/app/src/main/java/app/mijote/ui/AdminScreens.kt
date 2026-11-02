package app.mijote.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.AdminState
import app.mijote.data.Account
import app.mijote.data.Invitation
import app.mijote.data.NewInvitation
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Common layout of the administration screens: Butter header with a back arrow, cards below. */
@Composable
private fun AdminLayout(title: String, subtitle: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Sunny.Cream).imePadding().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().background(Sunny.Butter, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))) {
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 64.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Sunny.Ink)
                }
                Box(Modifier.padding(start = 12.dp, top = 20.dp)) { ScreenTitle(title, subtitle, size = 36) }
            }
        }
        Column(
            Modifier.offset(y = (-40).dp).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Sunny.InkSoft,
        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp))
}

private fun roleLabel(role: String) = when (role) { "admin" -> "administrateur"; "readonly" -> "lecture seule"; else -> "membre" }

private val dayMonth = DateTimeFormatter.ofPattern("d MMMM 'à' HH:mm", Locale.FRENCH)

private fun expiry(iso: String): String =
    runCatching { dayMonth.format(OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())) }.getOrDefault(iso)

/**
 * Invitations from the app (FR-ONB-02): create one (role, lifetime), then share its link through any
 * app of the phone or show its QR code; pending invitations can be revoked.
 */
@Composable
fun InvitationsScreen(
    state: AdminState,
    onCreate: (role: String, days: Int) -> Unit,
    onRevoke: (Invitation) -> Unit,
    onDismissCreated: () -> Unit,
    onBack: () -> Unit,
) {
    val inv = state.invitations
    var role by remember { mutableStateOf("member") }
    var days by remember(inv?.defaultDays) { mutableIntStateOf(inv?.defaultDays ?: 7) }
    AdminLayout("Invitations", "Faire rejoindre la famille", onBack) {
        state.created?.let { CreatedInvitation(it, onDismissCreated) }
        SunnyCard {
            Label("NOUVELLE INVITATION")
            Text("La personne choisit elle-même son identifiant et son mot de passe.", fontSize = 13.sp, color = Sunny.InkSoft)
            Label("RÔLE")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Membre", role == "member", Modifier.weight(1f)) { role = "member" }
                Chip("Lecture seule", role == "readonly", Modifier.weight(1f)) { role = "readonly" }
            }
            Label("VALABLE")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (inv?.durations ?: listOf(1, 7, 30)).forEach { d ->
                    Chip(if (d == 1) "1 jour" else "$d jours", days == d, Modifier.weight(1f)) { days = d }
                }
            }
            PrimaryButton("Créer l'invitation", state.busy) { onCreate(role, days) }
        }
        ErrorText(state.error)
        SunnyCard {
            Label("EN ATTENTE")
            when {
                inv == null -> Text(if (state.busy) "Chargement…" else "—", color = Sunny.InkSoft)
                inv.list.isEmpty() -> Text("Aucune invitation en attente.", color = Sunny.InkSoft, fontSize = 14.sp)
                else -> inv.list.forEach { i ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(roleLabel(i.role).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold, color = Sunny.Ink)
                            Text("Expire le ${expiry(i.expiresAt)}", fontSize = 12.sp, color = Sunny.InkSoft)
                        }
                        TextButton(onClick = { onRevoke(i) }, enabled = !state.busy) { Text("Révoquer", color = Sunny.Error) }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The invitation just created: shown once, with its QR code and the link to share. */
@Composable
private fun CreatedInvitation(n: NewInvitation, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val qr = remember(n) { n.qrPng?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    SunnyCard {
        Label("INVITATION CRÉÉE")
        Text("À usage unique, valable ${if (n.days == 1) "1 jour" else "${n.days} jours"} (${roleLabel(n.role)}). " +
            "Partagez-la maintenant : le code ne sera plus affiché.", fontSize = 14.sp, color = Sunny.Ink)
        qr?.let {
            Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                Image(it, contentDescription = "QR code de l'invitation", filterQuality = FilterQuality.None, modifier = Modifier.size(200.dp))
            }
            Text("À scanner avec l'appareil photo du téléphone de la personne.", fontSize = 12.sp, color = Sunny.InkSoft,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        Text(n.link, fontSize = 13.sp, color = Sunny.InkTitle, modifier = Modifier.padding(top = 12.dp)
            .background(Sunny.ButterLight, Shapes.Control).padding(12.dp).fillMaxWidth())
        PrimaryButton("Partager le lien", busy = false) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Invitation Mijote")
                .putExtra(Intent.EXTRA_TEXT, "Rejoins notre famille sur Mijote pour partager les menus : ${n.link}")
            context.startActivity(Intent.createChooser(send, "Partager l'invitation"))
        }
        Row {
            SecondaryAction("Copier le lien", enabled = true) { clipboard.setText(AnnotatedString(n.link)) }
        }
        SecondaryAction("Terminé", enabled = true, onClick = onDismiss)
    }
}

/** Accounts (FR-ADM-02): change a member's role, deactivate or reactivate it, set a new password. */
@Composable
fun AccountsScreen(
    state: AdminState,
    onRole: (Account, String) -> Unit,
    onDisabled: (Account, Boolean) -> Unit,
    onPassword: (Account, String) -> Unit,
    onBack: () -> Unit,
) {
    var resetting by remember { mutableStateOf<Account?>(null) }
    resetting?.let { a ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resetting = null },
            containerColor = Sunny.Card,
            title = { Text("Nouveau mot de passe pour ${a.displayName}", fontWeight = FontWeight.Bold, color = Sunny.Ink, fontSize = 18.sp) },
            text = {
                Column {
                    Text("Ses appareils seront déconnectés. 8 caractères minimum.", fontSize = 13.sp, color = Sunny.InkSoft)
                    SunnyField(pw, { pw = it }, "Mot de passe", keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(onClick = { onPassword(a, pw); resetting = null }, enabled = pw.length >= 8) {
                    Text("Enregistrer", color = Sunny.PeachDeep, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { resetting = null }) { Text("Annuler", color = Sunny.Slate) } },
        )
    }
    AdminLayout("Comptes", "Les membres de la famille", onBack) {
        ErrorText(state.error)
        val list = state.accounts
        if (list == null) SunnyCard { Text(if (state.busy) "Chargement…" else "—", color = Sunny.InkSoft) }
        list?.forEach { a ->
            SunnyCard {
                Text(a.displayName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink)
                Text("@${a.username} · ${roleLabel(a.role)}${if (a.disabled) " · désactivé" else ""}",
                    fontSize = 13.sp, color = if (a.disabled) Sunny.Error else Sunny.InkSoft)
                if (a.role != "admin") {
                    Label("RÔLE")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Membre", a.role == "member", Modifier.weight(1f)) { if (a.role != "member" && !state.busy) onRole(a, "member") }
                        Chip("Lecture seule", a.role == "readonly", Modifier.weight(1f)) { if (a.role != "readonly" && !state.busy) onRole(a, "readonly") }
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = { onDisabled(a, !a.disabled) }, enabled = !state.busy) {
                            Text(if (a.disabled) "Réactiver" else "Désactiver", color = if (a.disabled) Sunny.PeachDeep else Sunny.Error)
                        }
                        TextButton(onClick = { resetting = a }, enabled = !state.busy) { Text("Nouveau mot de passe", color = Sunny.Slate) }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
