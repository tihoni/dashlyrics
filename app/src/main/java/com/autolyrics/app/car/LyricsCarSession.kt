package com.autolyrics.app.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class LyricsCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return LyricsCarScreen(carContext)
    }
}
