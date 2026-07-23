package com.vicmobilecams.auto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.osmdroid.api.IGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.simplefastpoint.LabelledGeoPoint
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import java.io.File

/** Melbourne CBD -- shown before a GPS fix is available. */
private const val FALLBACK_LAT = -37.8136
private const val FALLBACK_LON = 144.9631
private const val DEFAULT_ZOOM = 11.0
private const val CENTER_ON_USER_ZOOM = 15.0

/** Full-screen map of every camera location, using OpenStreetMap tiles via osmdroid (no API key required). */
class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private var cameras: List<Camera> = emptyList()
    private var hasCenteredOnUser = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) myLocationOverlay.enableMyLocation()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        configureOsmdroid()
        super.onCreate(savedInstanceState)

        mapView = MapView(this)
        setContentView(mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(DEFAULT_ZOOM)
        mapView.controller.setCenter(GeoPoint(FALLBACK_LAT, FALLBACK_LON))

        cameras = CameraRepository.loadAll(applicationContext)
        addCameraOverlay()
        addMyLocationOverlay()

        if (hasLocationPermission()) {
            myLocationOverlay.enableMyLocation()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    private fun configureOsmdroid() {
        val config = Configuration.getInstance()
        config.load(applicationContext, getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))
        config.userAgentValue = packageName
        // Use app-private cache storage so no storage permission is needed for tile caching.
        config.osmdroidBasePath = File(cacheDir, "osmdroid")
        config.osmdroidTileCache = File(config.osmdroidBasePath, "tiles")
    }

    private fun addCameraOverlay() {
        val points = ArrayList<IGeoPoint>(cameras.size)
        for (camera in cameras) {
            points.add(LabelledGeoPoint(camera.lat, camera.lon, "${camera.location}, ${camera.suburb}"))
        }

        val theme = SimplePointTheme(points, true)
        val style = SimpleFastPointOverlayOptions.getDefaultStyle()
            .setRadius(9f)
            .setIsClickable(true)
            .setCellSize(20)

        val overlay = SimpleFastPointOverlay(theme, style)
        overlay.setOnClickListener(SimpleFastPointOverlay.OnClickListener { _, pointIndex ->
            val camera = pointIndex?.let { cameras.getOrNull(it) } ?: return@OnClickListener
            Toast.makeText(
                this,
                "${camera.location}, ${camera.suburb} — audited ${camera.auditDate}",
                Toast.LENGTH_LONG,
            ).show()
        })
        mapView.overlays.add(overlay)
    }

    private fun addMyLocationOverlay() {
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        myLocationOverlay.runOnFirstFix {
            val location = myLocationOverlay.myLocation ?: return@runOnFirstFix
            runOnUiThread {
                if (!hasCenteredOnUser) {
                    mapView.controller.animateTo(location)
                    mapView.controller.setZoom(CENTER_ON_USER_ZOOM)
                    hasCenteredOnUser = true
                }
            }
        }
        mapView.overlays.add(myLocationOverlay)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
}
