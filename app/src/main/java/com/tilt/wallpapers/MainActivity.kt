package com.tilt.wallpapers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tilt.wallpapers.data.Repo
import com.tilt.wallpapers.ui.AppRoot
import com.tilt.wallpapers.ui.theme.TiltTheme

class MainActivity : ComponentActivity() {
    // Language changes are handled in place (the UI text follows I18n), not by recreating the screen,
    // which flashed black.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Repo.refreshLanguage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The phone's language (or this app's language in the phone's settings) may have changed meanwhile.
        Repo.refreshLanguage()
        setContent {
            val state by Repo.state.collectAsStateWithLifecycle()
            TiltTheme(state) { AppRoot() }
        }
    }
}
