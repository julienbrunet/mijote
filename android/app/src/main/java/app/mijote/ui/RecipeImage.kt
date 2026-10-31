package app.mijote.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/** Loads a recipe thumbnail from its API path (authenticated, cached by the app); null when unavailable. */
val LocalImageLoader = compositionLocalOf<suspend (String) -> Bitmap?> { { null } }

/** The recipe picture filling its parent (clip it from outside), or [emoji] on a soft background without picture. */
@Composable
fun RecipeThumb(path: String?, emoji: String, modifier: Modifier = Modifier, emojiSize: TextUnit = 40.sp) {
    val load = LocalImageLoader.current
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let { load(it)?.asImageBitmap() }
    }
    Box(modifier.background(Sunny.ButterLight), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(emoji, style = TextStyle(fontSize = emojiSize))
    }
}
