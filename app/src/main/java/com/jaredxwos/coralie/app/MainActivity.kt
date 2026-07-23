package com.jaredxwos.coralie.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.jaredxwos.coralie.app.navigation.AppNavHost
import com.jaredxwos.coralie.ui.theme.HtmlHosterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // MaterialTheme is a stand-in for Phase 0 only. Phase 1 replaces
            // this with the real ColorScheme/Typography/Shapes + gradientBrush.
            HtmlHosterTheme {
                Surface(Modifier.background(Color.Black).safeDrawingPadding().fillMaxHeight()) {
                    AppNavHost(
                        container =
                            (application as CoralieApplication)
                                .container,
                    )
                }
            }
        }
    }
}