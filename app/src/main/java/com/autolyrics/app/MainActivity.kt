package com.autolyrics.app

import android.content.ComponentName
import android.os.Bundle
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.autolyrics.app.service.PlaybackSyncEngine
import com.autolyrics.app.service.SpotifySessionListener
import com.autolyrics.app.ui.PhoneLyricsScreen
import com.autolyrics.app.ui.theme.AutoLyricsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaybackSyncEngine.initialize(this)

        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, SpotifySessionListener::class.java)
            )
        } catch (_: Exception) {
        }

        setContent {
            AutoLyricsTheme {
                PhoneLyricsScreen()
            }
        }
    }
}
