package com.kaguya.comicsviewer

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.kaguya.comicsviewer.ui.KaguyaApp
import com.kaguya.comicsviewer.ui.theme.KaguyaTheme
import com.kaguya.comicsviewer.ui.theme.LocalSpacing
import com.kaguya.comicsviewer.ui.theme.Spacing
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("MainActivity", "onCreate started")
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d("MainActivity", "setting content")
        setContent {
            KaguyaTheme {
                CompositionLocalProvider(LocalSpacing provides Spacing()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        KaguyaApp()
                    }
                }
            }
        }
        Log.d("MainActivity", "onCreate done")
    }
}
