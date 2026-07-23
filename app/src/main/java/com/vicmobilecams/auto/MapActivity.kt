package com.vicmobilecams.auto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vicmobilecams.auto.databinding.ActivityMapBinding
import org.osmdroid.api.IGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
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

/**
 * Full-screen map of every camera location, using OpenStreetMap tiles via osmdroid (no API key
 * required), plus destination search + turn-by-turn routing via OSRM's public demo server (real
 * routes over the OSM road network, no live traffic data). Mirrors CameraNavigationScreen's
 * routing on the Android Auto side, but on the phone screen where it's actually testable.
 */
class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameras: List<Camera> = emptyList()
    private var routeLine: Polyline? = null
    private var hasCenteredOnUser = false
    private var isSearching = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) myLocationOverlay.enableMyLocation()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        configureOsmdroid()
        super.onCreate(savedInstanceState)

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(DEFAULT_ZOOM)
        binding.mapView.controller.setCenter(GeoPoint(FALLBACK_LAT, FALLBACK_LON))

        cameras = CameraRepository.loadAll(applicationContext)
        addCameraOverlay()
        addMyLocationOverlay()

        if (hasLocationPermission()) {
            myLocationOverlay.enableMyLocation()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        binding.searchButton.setOnClickListener { runSearch() }
        binding.destinationInput.setOnEditorActionListener { _, actionId, event ->
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSearchAction) {
                runSearch()
                true
            } else {
                false
            }
        }
        binding.clearRouteButton.setOnClickListener { clearRoute() }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    private fun configureOsmdroid() {
        val config = Configuration.getInstance()
        config.load(applicationContext, getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))
        config.userAgentValue = packageName
        // Use app-private cache storage so no storage permission is needed for tile caching.
        config.osmdroidBasePath = File(cacheDir, "osmdroid")
        config.osmdroidTileCache = File(config.osmdroidBasePath, "tiles")
    }

    private fun runSearch() {
        val query = binding.destinationInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty() || isSearching) return
        isSearching = true

        Thread {
            val results = NominatimSearchClient.search(query)
            mainHandler.post {
                isSearching = false
                val destination = results.firstOrNull()
                if (destination == null) {
                    Toast.makeText(this, R.string.no_results_message, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.routing_to_message, destination.displayName), Toast.LENGTH_SHORT).show()
                    startRouting(destination)
                }
            }
        }.start()
    }

    private fun startRouting(destination: SearchResult) {
        val origin = myLocationOverlay.myLocation
        val originLat = origin?.latitude ?: FALLBACK_LAT
        val originLon = origin?.longitude ?: FALLBACK_LON

        Thread {
            val route = OsrmRoutingClient.fetchRoute(originLat, originLon, destination.lat, destination.lon)
            mainHandler.post {
                if (route == null) {
                    Toast.makeText(this, R.string.route_not_found_message, Toast.LENGTH_LONG).show()
                } else {
                    drawRoute(route)
                }
            }
        }.start()
    }

    private fun drawRoute(route: Route) {
        routeLine?.let { binding.mapView.overlays.remove(it) }

        val polyline = Polyline().apply {
            outlinePaint.color = 0xFF1E88E5.toInt()
            outlinePaint.strokeWidth = 14f
            setPoints(route.points.map { GeoPoint(it[0], it[1]) })
        }
        binding.mapView.overlays.add(polyline)
        routeLine = polyline
        binding.clearRouteButton.visibility = android.view.View.VISIBLE

        binding.mapView.zoomToBoundingBox(polyline.bounds, true, 100)
        binding.mapView.invalidate()
    }

    private fun clearRoute() {
        routeLine?.let { binding.mapView.overlays.remove(it) }
        routeLine = null
        binding.clearRouteButton.visibility = android.view.View.GONE
        binding.mapView.invalidate()
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
        binding.mapView.overlays.add(overlay)
    }

    private fun addMyLocationOverlay() {
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView)
        myLocationOverlay.runOnFirstFix {
            val location = myLocationOverlay.myLocation ?: return@runOnFirstFix
            runOnUiThread {
                if (!hasCenteredOnUser) {
                    binding.mapView.controller.animateTo(location)
                    binding.mapView.controller.setZoom(CENTER_ON_USER_ZOOM)
                    hasCenteredOnUser = true
                }
            }
        }
        binding.mapView.overlays.add(myLocationOverlay)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
}
