package com.vicmobilecams.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class CameraCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CameraListScreen(carContext)
}
