package com.vicmobilecams.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class CameraCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // This app is not distributed through Google Play / Android Auto's verified app list,
        // so it is sideloaded with Android Auto Developer Mode's "Unknown sources" setting
        // enabled. Allow any host in that scenario.
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session = CameraCarSession()
}
