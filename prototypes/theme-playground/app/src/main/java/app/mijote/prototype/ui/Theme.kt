package app.mijote.prototype.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.mijote.prototype.R

// "Calm Kit" palette. All theme colors live here so they can be tuned in one place.
object Calm {
    val Sage = Color(0xFF6B7F6E)
    val Moss = Color(0xFFA7B8A7)
    val Mist = Color(0xFFD9E1D9)
    val Ivory = Color(0xFFF4EFE8)
    val Sand = Color(0xFFC9BDAF)
    val Background = Color(0xFFEFEAE3)
    val Card = Color(0xFFF7F4EF)
    val Ink = Color(0xFF2E3530)
    val InkSoft = Color(0xFF7D847F)
    val Track = Color(0xFFDDD8D1)
}

// Dish type tags, kept within the calm palette (background, text).
object DishColors {
    val meat = Color(0xFFEBDDD6) to Color(0xFF7E5A4E)
    val fish = Color(0xFFDDE4E8) to Color(0xFF4F6573)
    val veggie = Calm.Mist to Color(0xFF4F6552)
}

val Inter = FontFamily(
    Font(R.font.inter_300, FontWeight.Light),
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
)

private val Colors = lightColorScheme(
    primary = Calm.Sage,
    onPrimary = Color.White,
    primaryContainer = Calm.Mist,
    onPrimaryContainer = Calm.Ink,
    secondary = Calm.Moss,
    onSecondary = Calm.Ink,
    tertiary = Calm.Sand,
    background = Calm.Background,
    onBackground = Calm.Ink,
    surface = Calm.Card,
    onSurface = Calm.Ink,
    surfaceVariant = Calm.Ivory,
    onSurfaceVariant = Calm.InkSoft,
    outline = Calm.Sand,
    outlineVariant = Calm.Track,
)

private val Base = Typography()
private val Type = Typography(
    bodyLarge = Base.bodyLarge.copy(fontFamily = Inter),
    bodyMedium = Base.bodyMedium.copy(fontFamily = Inter),
    bodySmall = Base.bodySmall.copy(fontFamily = Inter),
    labelMedium = Base.labelMedium.copy(fontFamily = Inter),
    labelSmall = Base.labelSmall.copy(fontFamily = Inter),
)

@Composable
fun MijoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type) {
        androidx.compose.material3.ProvideTextStyle(TextStyle(fontFamily = Inter), content)
    }
}
