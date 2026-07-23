package com.vicmobilecams.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vicmobilecams.auto.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updatePermissionButton(hasLocationPermission())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        binding.instructionsText.text = getString(R.string.phone_instructions)

        binding.grantPermissionButton.setOnClickListener {
            requestPermissionsLauncher.launch(requiredPermissions())
        }
        updatePermissionButton(hasLocationPermission())

        binding.toggleAlertsButton.setOnClickListener { toggleAlerts() }
        updateToggleButton()

        binding.openMapButton.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
    }

    private fun toggleAlerts() {
        val currentlyEnabled = prefs.getBoolean(KEY_ALERTS_ENABLED, false)
        if (currentlyEnabled) {
            CameraAlertService.stop(this)
        } else {
            CameraAlertService.start(this)
        }
        prefs.edit().putBoolean(KEY_ALERTS_ENABLED, !currentlyEnabled).apply()
        updateToggleButton()
    }

    private fun updateToggleButton() {
        val enabled = prefs.getBoolean(KEY_ALERTS_ENABLED, false)
        binding.toggleAlertsButton.text = if (enabled) {
            getString(R.string.stop_alerts_button)
        } else {
            getString(R.string.start_alerts_button)
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun updatePermissionButton(granted: Boolean) {
        binding.grantPermissionButton.isEnabled = !granted
        binding.grantPermissionButton.text = if (granted) {
            getString(R.string.location_permission_granted)
        } else {
            getString(R.string.grant_location_permission)
        }
    }

    companion object {
        private const val PREFS_NAME = "vic_cam_alerts_prefs"
        private const val KEY_ALERTS_ENABLED = "alerts_enabled"
    }
}
