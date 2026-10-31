package app.mijote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.data.User

enum class Tab(val label: String) { MENUS("Menus"), RECIPES("Recettes"), SETTINGS("Réglages") }

/** Signed-in shell: header with the user button, empty tab content, floating bottom bar. */
@Composable
fun HomeScreen(user: User, onLogout: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.MENUS) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar(tab) { tab = it } },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    ScreenTitle(if (tab == Tab.MENUS) "Ça mijote" else tab.label)
                }
                UserButton(user, onLogout)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when (tab) {
                        Tab.MENUS -> "Les menus de la semaine arriveront ici."
                        Tab.RECIPES -> "Les recettes arriveront ici."
                        Tab.SETTINGS -> "Les réglages arriveront ici."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun UserButton(user: User, onLogout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(44.dp).background(Calm.Card, CircleShape)) {
            Icon(Icons.Filled.Person, contentDescription = "Utilisateur", tint = Calm.Sage)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Calm.Card) {
            Text(user.displayName, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            DropdownMenuItem(text = { Text("Se déconnecter") }, onClick = { open = false; onLogout() })
        }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val icons = mapOf(Tab.MENUS to Icons.Filled.Home, Tab.RECIPES to Icons.Filled.Search, Tab.SETTINGS to Icons.Filled.Settings)
    Card(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Calm.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { t ->
                val on = t == selected
                val color = if (on) Calm.Sage else Calm.InkSoft
                Column(
                    Modifier.clickable { onSelect(t) }.padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icons.getValue(t), contentDescription = t.label, tint = color)
                    Text(t.label, fontSize = 12.sp, color = color, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(4.dp).background(if (on) Calm.Sage else Color.Transparent, CircleShape))
                }
            }
        }
    }
}
