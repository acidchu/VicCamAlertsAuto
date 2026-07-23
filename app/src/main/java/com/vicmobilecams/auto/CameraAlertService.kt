package com.vicmobilecams.auto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that watches GPS location and fires a notification when the car comes
 * within [ALERT_RADIUS_METERS] of an approved mobile camera location -- the Waze-style
 * "heads up" behaviour. Started when a session connects to Android Auto (see
 * CameraCarSession) and stoppable manually from MainActivity.
 *
 * This is straight-line proximity, not route-aware: unlike Waze it has no routing engine, so it
 * can alert for a camera that's close by on a different road. Good enough for a heads-up; not a
 * guarantee.
 */
class CameraAlertService : Service() {

    private lateinit var locationManager: LocationManager
    private var lastAlertedKey: String? = null
    private var lastAlertTimeMs = 0L

    private val locationListener = LocationListener { location -> checkNearbyCameras(location) }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        startMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        locationManager.removeUpdates(locationListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoring() {
        val notification = buildMonitoringNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(MONITORING_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(MONITORING_NOTIFICATION_ID, notification)
        }

        if (!hasLocationPermission()) return

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        LOCATION_UPDATE_INTERVAL_MS,
                        LOCATION_UPDATE_MIN_DISTANCE_M,
                        locationListener,
                    )
                }
            } catch (_: SecurityException) {
                // Permission revoked mid-flight -- this provider just won't report updates.
            }
        }
    }

    private fun stopMonitoring() {
        locationManager.removeUpdates(locationListener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun checkNearbyCameras(location: Location) {
        val cameras = CameraRepository.loadAll(applicationContext)
        val nearest = cameras.minByOrNull { it.distanceKmFrom(location.latitude, location.longitude) } ?: return

        val distanceMeters = nearest.distanceKmFrom(location.latitude, location.longitude) * 1000.0
        val key = "${nearest.location}|${nearest.suburb}"
        val now = System.currentTimeMillis()

        when {
            distanceMeters <= ALERT_RADIUS_METERS -> {
                val recentlyAlertedForThisCamera =
                    key == lastAlertedKey && (now - lastAlertTimeMs) < RE_ALERT_COOLDOWN_MS
                if (!recentlyAlertedForThisCamera) {
                    showCameraAlert(nearest, distanceMeters)
                    lastAlertedKey = key
                    lastAlertTimeMs = now
                }
            }
            distanceMeters > CLEAR_RADIUS_METERS && key == lastAlertedKey -> {
                lastAlertedKey = null
            }
        }
    }

    private fun showCameraAlert(camera: Camera, distanceMeters: Double) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.camera_alert_title))
            .setContentText(
                getString(
                    R.string.camera_alert_body,
                    camera.location,
                    camera.suburb,
                    distanceMeters.toInt(),
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()

        val notificationId = ALERT_NOTIFICATION_ID_BASE + (camera.hashCode() and 0xFFFF)
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildMonitoringNotification(): Notification {
        return NotificationCompat.Builder(this, MONITORING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.monitoring_notification_title))
            .setContentText(getString(R.string.monitoring_notification_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.alert_channel_description)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(MONITORING_CHANNEL_ID, getString(R.string.monitoring_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_STOP = "com.vicmobilecams.auto.action.STOP_MONITORING"

        private const val ALERT_CHANNEL_ID = "camera_alerts"
        private const val MONITORING_CHANNEL_ID = "camera_monitoring"
        private const val MONITORING_NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID_BASE = 1000

        private const val ALERT_RADIUS_METERS = 500.0
        private const val CLEAR_RADIUS_METERS = 900.0
        private const val RE_ALERT_COOLDOWN_MS = 5 * 60 * 1000L
        private const val LOCATION_UPDATE_INTERVAL_MS = 10_000L
        private const val LOCATION_UPDATE_MIN_DISTANCE_M = 50f

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CameraAlertService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CameraAlertService::class.java).setAction(ACTION_STOP))
        }
    }
}
