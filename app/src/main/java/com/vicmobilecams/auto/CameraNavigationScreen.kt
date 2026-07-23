package com.vicmobilecams.auto

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.max
import kotlin.math.min

/** Melbourne CBD -- shown before a GPS fix is available. */
private const val FALLBACK_LAT = -37.8136
private const val FALLBACK_LON = 144.9631
private const val DEFAULT_ZOOM = 15
private const val MIN_ZOOM = 3
private const val MAX_ZOOM = 19
private const val PINCH_ZOOM_STEP_THRESHOLD = 1.4

/**
 * Live, pannable/zoomable street map for the car screen -- the "Waze-style" primary experience.
 * androidx.car.app has no ready-made map template; NavigationTemplate just hosts a Surface that
 * the app draws into directly, so this fetches OpenStreetMap tiles and renders them (plus camera
 * markers and a self marker) with the Canvas API. No turn-by-turn guidance is offered, so
 * NavigationManager.navigationStarted() is intentionally not used -- a live map without route
 * guidance is a supported NavigationTemplate use case.
 */
class CameraNavigationScreen(carContext: CarContext) : Screen(carContext) {

    private val tileCache = MapTileCache(carContext)
    private val locationManager = carContext.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameras: List<Camera> = CameraRepository.loadAll(carContext)

    private var surface: android.view.Surface? = null
    private var centerLat = FALLBACK_LAT
    private var centerLon = FALLBACK_LON
    private var zoom = DEFAULT_ZOOM
    private var followingGps = true
    private var pinchAccumulator = 1.0
    private var hasRequestedPermission = false

    private val locationListener = LocationListener { location ->
        if (followingGps) {
            centerLat = location.latitude
            centerLon = location.longitude
        }
        drawFrame()
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            surface = surfaceContainer.surface
            drawFrame()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            surface = null
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            followingGps = false
            val world = MercatorProjection.toWorldPixel(centerLat, centerLon, zoom)
            // If panning feels inverted once tested on a real head unit, flip these signs.
            val newLatLon = MercatorProjection.fromWorldPixel(world[0] + distanceX, world[1] + distanceY, zoom)
            centerLat = newLatLon[0]
            centerLon = newLatLon[1]
            drawFrame()
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            pinchAccumulator *= scaleFactor
            when {
                pinchAccumulator > PINCH_ZOOM_STEP_THRESHOLD -> {
                    zoom = min(zoom + 1, MAX_ZOOM)
                    pinchAccumulator = 1.0
                    drawFrame()
                }
                pinchAccumulator < 1.0 / PINCH_ZOOM_STEP_THRESHOLD -> {
                    zoom = max(zoom - 1, MIN_ZOOM)
                    pinchAccumulator = 1.0
                    drawFrame()
                }
            }
        }
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        startLocationUpdates()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                locationManager.removeUpdates(locationListener)
            }
        })
    }

    override fun onGetTemplate(): Template {
        ensureLocationPermission()
        return NavigationTemplate.Builder()
            .setActionStrip(buildActionStrip())
            .build()
    }

    private fun buildActionStrip(): ActionStrip {
        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_recenter)).build())
                    .setOnClickListener {
                        followingGps = true
                        drawFrame()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_list)).build())
                    .setOnClickListener { screenManager.push(CameraListScreen(carContext)) }
                    .build()
            )
            .build()
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 2_000L, 5f, locationListener)
                }
            } catch (_: SecurityException) {
                // Permission revoked mid-flight -- this provider just won't report updates.
            }
        }
    }

    private fun ensureLocationPermission() {
        if (hasLocationPermission() || hasRequestedPermission) return
        hasRequestedPermission = true
        carContext.requestPermissions(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) { _, _ -> startLocationUpdates() }
    }

    private fun hasLocationPermission(): Boolean {
        return carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun drawFrame() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { drawFrame() }
            return
        }
        val surface = surface ?: return
        if (!surface.isValid) return
        val canvas = try {
            surface.lockCanvas(null)
        } catch (_: Exception) {
            return
        }
        try {
            renderMap(canvas)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun renderMap(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#DDDDDD"))

        val width = canvas.width
        val height = canvas.height
        val centerWorld = MercatorProjection.toWorldPixel(centerLat, centerLon, zoom)
        val screenCenterX = width / 2.0
        val screenCenterY = height / 2.0

        drawTiles(canvas, centerWorld, screenCenterX, screenCenterY, width, height)
        drawCameras(canvas, centerWorld, screenCenterX, screenCenterY, width, height)
        drawSelfMarker(canvas, screenCenterX, screenCenterY)
    }

    private fun drawTiles(
        canvas: Canvas,
        centerWorld: DoubleArray,
        screenCenterX: Double,
        screenCenterY: Double,
        width: Int,
        height: Int,
    ) {
        val tileSize = MercatorProjection.TILE_SIZE
        val startTileX = ((centerWorld[0] - screenCenterX) / tileSize).toInt() - 1
        val endTileX = ((centerWorld[0] + screenCenterX) / tileSize).toInt() + 1
        val startTileY = ((centerWorld[1] - screenCenterY) / tileSize).toInt() - 1
        val endTileY = ((centerWorld[1] + screenCenterY) / tileSize).toInt() + 1
        val tilesPerSide = 1 shl zoom

        for (tileX in startTileX..endTileX) {
            val wrappedTileX = ((tileX % tilesPerSide) + tilesPerSide) % tilesPerSide
            for (tileY in startTileY..endTileY) {
                if (tileY < 0 || tileY >= tilesPerSide) continue
                val left = (tileX * tileSize - centerWorld[0] + screenCenterX).toFloat()
                val top = (tileY * tileSize - centerWorld[1] + screenCenterY).toFloat()

                val bitmap = tileCache.getCachedTile(zoom, wrappedTileX, tileY)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, left, top, null)
                } else {
                    tileCache.requestTile(zoom, wrappedTileX, tileY) { _, _, _ -> drawFrame() }
                }
            }
        }
    }

    private fun drawCameras(
        canvas: Canvas,
        centerWorld: DoubleArray,
        screenCenterX: Double,
        screenCenterY: Double,
        width: Int,
        height: Int,
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935")
            style = Paint.Style.FILL
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        for (camera in cameras) {
            val world = MercatorProjection.toWorldPixel(camera.lat, camera.lon, zoom)
            val x = (world[0] - centerWorld[0] + screenCenterX).toFloat()
            val y = (world[1] - centerWorld[1] + screenCenterY).toFloat()
            if (x < -20 || x > width + 20 || y < -20 || y > height + 20) continue
            canvas.drawCircle(x, y, 8f, fill)
            canvas.drawCircle(x, y, 8f, outline)
        }
    }

    private fun drawSelfMarker(canvas: Canvas, x: Double, y: Double) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E88E5")
            style = Paint.Style.FILL
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(x.toFloat(), y.toFloat(), 12f, fill)
        canvas.drawCircle(x.toFloat(), y.toFloat(), 12f, outline)
    }
}
