package com.amll.droidmate.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat

/**
 * Placeholder activity for lyrics-only display.
 * Keeps manifest references valid until dedicated UI is implemented.
 */
class LyricsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.statusBarsPadding()
            ) {
                Text(text = "Lyrics screen is not implemented yet")
            }
        }
    }
}
