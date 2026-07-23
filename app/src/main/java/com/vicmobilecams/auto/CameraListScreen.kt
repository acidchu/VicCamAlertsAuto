package com.vicmobilecams.auto

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.text.Spannable
import android.text.SpannableString
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/** Melbourne CBD -- used as a fallback centre point before a real location fix is available. */
private const val FALLBACK_LAT = -37.8136
private const val FALLBACK_LON = 144.9631
private const val DEFAULT_LIST_LIMIT = 25

class CameraListScreen(carContext: CarContext) : Screen(carContext) {

    private var hasRequestedPermission = false

    override fun onGetTemplate(): Template {
        ensureLocationPermission()

        val (lat, lon) = currentOrFallbackLocation()
        val listLimit = contentLimit()
        val cameras = CameraRepository.nearest(carContext, lat, lon, listLimit)

        val itemListBuilder = ItemList.Builder()
        for (camera in cameras) {
            itemListBuilder.addItem(buildRow(camera, lat, lon))
        }

        return PlaceListMapTemplate.Builder()
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setCurrentLocationEnabled(hasLocationPermission())
            .setItemList(itemListBuilder.build())
            .build()
    }

    private fun buildRow(camera: Camera, fromLat: Double, fromLon: Double): Row {
        val distanceKm = camera.distanceKmFrom(fromLat, fromLon)
        val distanceText = SpannableString(" ").apply {
            setSpan(
                DistanceSpan.create(Distance.create(distanceKm, Distance.UNIT_KILOMETERS)),
                0,
                1,
                Spannable.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }

        val place = Place.Builder(CarLocation.create(camera.lat, camera.lon))
            .setMarker(PlaceMarker.Builder().build())
            .build()

        return Row.Builder()
            .setTitle("${camera.location}, ${camera.suburb}")
            .addText(distanceText)
            .addText(carContext.getString(R.string.app_name) + " · updated " + camera.auditDate)
            .setMetadata(Metadata.Builder().setPlace(place).build())
            .setOnClickListener {
                CarToast.makeText(
                    carContext,
                    "${camera.location}, ${camera.suburb} — audited ${camera.auditDate}",
                    CarToast.LENGTH_LONG,
                ).show()
            }
            .build()
    }

    private fun currentOrFallbackLocation(): Pair<Double, Double> {
        if (!hasLocationPermission()) return FALLBACK_LAT to FALLBACK_LON

        val locationManager = carContext.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (provider in providers) {
            try {
                val last = locationManager?.getLastKnownLocation(provider)
                if (last != null) return last.latitude to last.longitude
            } catch (_: SecurityException) {
                // permission race -- fall through to fallback
            }
        }
        return FALLBACK_LAT to FALLBACK_LON
    }

    private fun hasLocationPermission(): Boolean {
        return carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureLocationPermission() {
        if (hasLocationPermission() || hasRequestedPermission) return
        hasRequestedPermission = true
        carContext.requestPermissions(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) { _, _ -> invalidate() }
    }

    private fun contentLimit(): Int {
        return try {
            val constraintManager = carContext.getCarService(ConstraintManager::class.java)
            constraintManager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
        } catch (_: Exception) {
            DEFAULT_LIST_LIMIT
        }
    }
}
