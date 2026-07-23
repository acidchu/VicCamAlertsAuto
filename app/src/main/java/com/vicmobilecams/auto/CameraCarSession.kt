package com.vicmobilecams.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class CameraCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        // Start proximity alerts for the duration of the Android Auto connection, and stop them
        // when the car disconnects so the phone isn't monitoring location in the background.
        CameraAlertService.start(carContext)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                CameraAlertService.stop(carContext)
            }
        })
        return CameraListScreen(carContext)
    }
}
