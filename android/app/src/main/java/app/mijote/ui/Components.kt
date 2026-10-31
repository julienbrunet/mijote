package app.mijote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mijote.R

/** Big bold screen title, soft grey (Sunny Kit). */
@Composable
fun ScreenTitle(text: String, subtitle: String? = null, size: Int = 30) {
    Column {
        Text(text, fontSize = size.sp, lineHeight = (size * 1.1).sp, fontWeight = FontWeight.Bold, color = Sunny.InkTitle)
        subtitle?.let { Text(it, fontSize = 14.sp, color = Sunny.InkTitle, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** The Mijote logo (cocotte) next to the "Mijote" wordmark. */
@Composable
fun BrandTitle(logoSize: Dp = 76.dp, textSize: Int = 54) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_mijote_logo), contentDescription = null, modifier = Modifier.size(logoSize))
        Text("Mijote", fontFamily = Grandstander, fontWeight = FontWeight.ExtraBold, fontSize = textSize.sp,
            lineHeight = textSize.sp, color = Sunny.InkTitle, modifier = Modifier.padding(start = 14.dp))
    }
}

/**
 * Colored block glued to the top-right corner of a sheet, rounded on its free bottom-left
 * corner (the signature shape of the theme). Holds one icon or a short emoji.
 */
@Composable
fun CornerBlock(color: Color, size: Dp = 88.dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.size(size).background(color, Shapes.CornerBlock),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Small light bar at the top of a stacked sheet. */
@Composable
fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(56.dp).height(5.dp).background(Color.White.copy(alpha = 0.6f), CircleShape))
    }
}

/**
 * Onboarding layout: a butter sheet with a big title and a white corner block, then the
 * form card overlapping its bottom edge.
 */
@Composable
fun OnboardingScaffold(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Sunny.Cream).imePadding().verticalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .background(Sunny.Butter, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp)),
        ) {
            CornerBlock(Sunny.Card, size = 104.dp, modifier = Modifier.align(Alignment.TopEnd)) {
                Text("🍲", fontSize = 36.sp, modifier = Modifier.statusBarsPadding())
            }
            Column(Modifier.statusBarsPadding().padding(start = 28.dp, end = 28.dp, top = 96.dp, bottom = 80.dp)) {
                ScreenTitle(title, subtitle, size = 40)
            }
        }
        Column(
            Modifier.offset(y = (-48).dp).widthIn(max = 460.dp).padding(horizontal = 20.dp).navigationBarsPadding(),
            content = content,
        )
    }
}

@Composable
fun SunnyCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().shadow(10.dp, Shapes.Card, ambientColor = Sunny.PeachDeep, spotColor = Sunny.PeachDeep)
            .background(Sunny.Card, Shapes.Card).padding(22.dp),
        content = content,
    )
}

/** Underlined, borderless text field, as in the search box of the reference design. */
@Composable
fun SunnyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Sunny.PeachDeep,
            unfocusedIndicatorColor = Sunny.Track,
            focusedLabelColor = Sunny.PeachDeep,
            unfocusedLabelColor = Sunny.InkSoft,
            cursorColor = Sunny.PeachDeep,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ErrorText(error: String?) {
    if (error == null) return
    Text(error, color = Sunny.Error, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            .background(Sunny.ErrorBg, Shapes.Control).padding(horizontal = 14.dp, vertical = 10.dp))
}

/** Peach button, bold white label, big radius. */
@Composable
fun PrimaryButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = Shapes.Control,
        colors = ButtonDefaults.buttonColors(containerColor = Sunny.PeachDeep, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp).height(56.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Grey link-like action shown under the onboarding card. */
@Composable
fun SecondaryAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Sunny.Slate, fontWeight = FontWeight.SemiBold)
    }
}

/** Dish type pill. */
@Composable
fun DishTag(label: String, colors: Pair<Color, Color>) {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.second,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.first).padding(horizontal = 8.dp, vertical = 3.dp))
}
