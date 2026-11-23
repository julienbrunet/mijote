package app.mijote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared heading for the three destinations in the main navigation. */
@Composable
internal fun AgendaHeader(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, fontSize = 26.sp, lineHeight = 29.sp,
                fontWeight = FontWeight.Bold, color = Sunny.Ink)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = Sunny.InkTitle)
        }
        actions()
    }
}

@Composable
internal fun AgendaAction(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    warning: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Text(label, Modifier.padding(start = if (icon == null) 0.dp else 6.dp),
            fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)
    }
    if (primary) {
        Button(
            onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 14.dp), shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (warning) Sunny.PeachLight else Sunny.Butter,
                contentColor = Sunny.Ink,
            ),
            content = content,
        )
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 10.dp), shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Sunny.Ink),
            border = BorderStroke(1.dp, Sunny.Track), content = content,
        )
    }
}
