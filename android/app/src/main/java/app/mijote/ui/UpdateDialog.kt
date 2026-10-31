package app.mijote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.UpdateState

/** Offers a newer published version; the system then asks to confirm the installation. */
@Composable
fun UpdateDialog(state: UpdateState, currentVersion: String, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        UpdateState.None -> return
    }
    val downloading = state is UpdateState.Downloading
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Sunny.Card,
        shape = Shapes.Card,
        title = { Text("Nouvelle version", fontWeight = FontWeight.Bold, color = Sunny.InkTitle) },
        text = {
            Column {
                Text("La version ${release.version} est disponible (vous avez la $currentVersion).", color = Sunny.Ink)
                if (downloading) {
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Sunny.PeachDeep, strokeWidth = 2.dp)
                        Text("Téléchargement…", fontSize = 14.sp, color = Sunny.InkSoft, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                ErrorText((state as? UpdateState.Available)?.error)
            }
        },
        confirmButton = {
            Button(onClick = onInstall, enabled = !downloading, shape = Shapes.Control,
                colors = ButtonDefaults.buttonColors(containerColor = Sunny.PeachDeep)) {
                Text("Mettre à jour", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (!downloading) TextButton(onClick = onDismiss) { Text("Plus tard", color = Sunny.Slate, fontWeight = FontWeight.SemiBold) }
        },
    )
}
