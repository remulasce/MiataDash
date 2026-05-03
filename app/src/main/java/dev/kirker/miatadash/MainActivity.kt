package dev.kirker.miatadash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.kirker.miatadash.ui.MiataNavHost
import dev.kirker.miatadash.ui.theme.MiataTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiataTheme {
                MiataNavHost()
            }
        }
    }
}
