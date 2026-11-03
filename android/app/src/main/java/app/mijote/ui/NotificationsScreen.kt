package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.NotificationsState
import app.mijote.data.RecipeNotification
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun NotificationsScreen(
    state: NotificationsState, onBack: () -> Unit, onRefresh: () -> Unit, onMore: () -> Unit,
    onOpen: (RecipeNotification) -> Unit, onRead: (RecipeNotification) -> Unit, onDelete: (RecipeNotification) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Sunny.Butter).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().background(Sunny.Card, Shapes.Card).statusBarsPadding().padding(20.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Sunny.Ink) }
            ScreenTitle("Notifications", "${state.unreadCount} non lue${if (state.unreadCount > 1) "s" else ""}", size = 30)
            Text("Les recettes qui font envie à la famille", fontSize = 14.sp, color = Sunny.InkTitle, modifier = Modifier.padding(top = 8.dp))
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ErrorText(state.error)
            OutlinedButton(onClick = onRefresh, enabled = !state.loading && state.working == null) { Text(if (state.loading) "Actualisation…" else "Actualiser") }
            if (state.items.isEmpty() && !state.loading && state.error == null) Text("Aucune notification pour le moment.", color = Sunny.Ink, modifier = Modifier.padding(vertical = 32.dp))
            state.items.forEach { item ->
                Column(Modifier.fillMaxWidth().clip(Shapes.Card).background(Sunny.Card).padding(18.dp)) {
                    if (item.readAt == null) Text("NOUVELLE ENVIE", color = Sunny.PeachDeep, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.fillMaxWidth().clickable(enabled = item.recipeAvailable && !state.loading && state.working == null, onClick = { onOpen(item) }).padding(vertical = 8.dp)) {
                        Text("${item.requesterName} a envie de cette recette", fontSize = 14.sp, color = Sunny.InkTitle)
                        Text(item.recipeTitle, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Sunny.Ink, modifier = Modifier.padding(top = 6.dp))
                        Text(DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.FRENCH).withZone(ZoneId.systemDefault()).format(item.createdAt),
                            fontSize = 12.sp, color = Sunny.InkTitle, modifier = Modifier.padding(top = 8.dp))
                        if (!item.recipeAvailable) Text("Recette archivée", color = Sunny.InkTitle, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (item.readAt == null) TextButton(onClick = { onRead(item) }, enabled = state.working == null && !state.loading) {
                            Text("Marquer comme lue", color = Sunny.PeachDeep)
                        } else Text("Lue", color = Sunny.InkTitle, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
                        TextButton(onClick = { onDelete(item) }, enabled = state.working == null && !state.loading) { Text("Effacer", color = Sunny.InkTitle) }
                    }
                }
            }
            if (state.hasMore) OutlinedButton(onClick = onMore, enabled = !state.loading && state.working == null) { Text("Afficher la suite") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
