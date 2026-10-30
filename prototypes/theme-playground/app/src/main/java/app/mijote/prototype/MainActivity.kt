package app.mijote.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.mijote.prototype.ui.HomeScreen
import app.mijote.prototype.ui.MijoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MijoteTheme { HomeScreen() }
        }
    }
}
