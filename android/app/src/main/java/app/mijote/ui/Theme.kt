package app.mijote.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mijote.R

// "Sunny Kit" palette: warm butter and peach sheets on a cream background, white cards,
// a soft slate grey as the neutral accent. All theme colors live here.
object Sunny {
    val Butter = Color(0xFFFFD88C)
    val ButterLight = Color(0xFFFFEBC2)
    val Peach = Color(0xFFFFA98B)
    val PeachDeep = Color(0xFFF4876A)
    val PeachLight = Color(0xFFFFE3D8)
    val Slate = Color(0xFF8E8E93)
    val Cream = Color(0xFFFFF5E6)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF4A4A4A)
    val InkTitle = Color(0xFF6B6B6B)
    val InkSoft = Color(0xFFA3A3A3)
    val Track = Color(0xFFF0E6D8)
    val Error = Color(0xFFC0583E)
    val ErrorBg = Color(0xFFFFE3D8)
}

// Dish type tags (background, text).
object DishColors {
    val meat = Color(0xFFFFE3D8) to Color(0xFFC0583E)
    val fish = Color(0xFFDDEBF4) to Color(0xFF4A7391)
    val veggie = Color(0xFFE4F1D8) to Color(0xFF5A7F45)
}

// Big, soft radii; sheets keep square bottoms when they stack on each other.
object Shapes {
    val Sheet = RoundedCornerShape(36.dp)
    val Card = RoundedCornerShape(22.dp)
    val Control = RoundedCornerShape(18.dp)
    val CornerBlock = RoundedCornerShape(topStart = 0.dp, topEnd = 36.dp, bottomEnd = 0.dp, bottomStart = 36.dp)
}

val Inter = FontFamily(
    Font(R.font.inter_300, FontWeight.Light),
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
)

private val Colors = lightColorScheme(
    primary = Sunny.PeachDeep,
    onPrimary = Color.White,
    primaryContainer = Sunny.Butter,
    onPrimaryContainer = Sunny.Ink,
    secondary = Sunny.Butter,
    onSecondary = Sunny.Ink,
    tertiary = Sunny.Slate,
    onTertiary = Color.White,
    background = Sunny.Cream,
    onBackground = Sunny.Ink,
    surface = Sunny.Card,
    onSurface = Sunny.Ink,
    surfaceVariant = Sunny.ButterLight,
    onSurfaceVariant = Sunny.InkSoft,
    outline = Sunny.Track,
    outlineVariant = Sunny.Track,
    error = Sunny.Error,
)

private val Base = Typography()
private val Type = Typography(
    bodyLarge = Base.bodyLarge.copy(fontFamily = Inter),
    bodyMedium = Base.bodyMedium.copy(fontFamily = Inter),
    bodySmall = Base.bodySmall.copy(fontFamily = Inter),
    labelLarge = Base.labelLarge.copy(fontFamily = Inter),
    labelMedium = Base.labelMedium.copy(fontFamily = Inter),
    labelSmall = Base.labelSmall.copy(fontFamily = Inter),
)

@Composable
fun MijoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type) {
        androidx.compose.material3.ProvideTextStyle(TextStyle(fontFamily = Inter), content)
    }
}
