package com.vicmobilecams.auto

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val maneuverType: String,
    val maneuverModifier: String?,
    val lat: Double,
    val lon: Double,
    val streetName: String,
)

data class Route(
    /** Full route geometry as [lat, lon] pairs, in travel order. */
    val points: List<DoubleArray>,
    val steps: List<RouteStep>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
)

/**
 * Fetches driving routes from OSRM's public demo server. That server is explicitly a
 * light/demo deployment, not meant for production traffic -- fine for one person's personal
 * driving, per https://github.com/Project-OSRM/osrm-backend/wiki/Demo-server. It has no live
 * traffic data; routes are computed purely from the OpenStreetMap road network.
 */
object OsrmRoutingClient {
    private const val BASE_URL = "https://router.project-osrm.org/route/v1/driving"
    private const val USER_AGENT =
        "VicCamAlertsAuto/1.0 (personal Android Auto project; contact bmsomething98@gmail.com)"

    fun fetchRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route? {
        val url = "$BASE_URL/$fromLon,$fromLat;$toLon,$toLat?steps=true&geometries=geojson&overview=full"
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            parseRoute(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRoute(body: String): Route? {
        val json = JSONObject(body)
        if (json.optString("code") != "Ok") return null
        val route = json.getJSONArray("routes").optJSONObject(0) ?: return null

        val coordinatesArray = route.getJSONObject("geometry").getJSONArray("coordinates")
        val points = ArrayList<DoubleArray>(coordinatesArray.length())
        for (i in 0 until coordinatesArray.length()) {
            val pair = coordinatesArray.getJSONArray(i)
            points.add(doubleArrayOf(pair.getDouble(1), pair.getDouble(0))) // GeoJSON is [lon, lat]
        }

        val steps = ArrayList<RouteStep>()
        val legs = route.getJSONArray("legs")
        for (legIndex in 0 until legs.length()) {
            val legSteps = legs.getJSONObject(legIndex).getJSONArray("steps")
            for (i in 0 until legSteps.length()) {
                val step = legSteps.getJSONObject(i)
                val maneuver = step.getJSONObject("maneuver")
                val location = maneuver.getJSONArray("location")
                val type = maneuver.optString("type", "turn")
                val modifier: String? = if (maneuver.has("modifier")) maneuver.getString("modifier") else null
                val streetName = step.optString("name", "")
                steps.add(
                    RouteStep(
                        instruction = describeStep(type, modifier, streetName),
                        distanceMeters = step.optDouble("distance", 0.0),
                        maneuverType = type,
                        maneuverModifier = modifier,
                        lat = location.getDouble(1),
                        lon = location.getDouble(0),
                        streetName = streetName,
                    )
                )
            }
        }

        return Route(
            points = points,
            steps = steps,
            totalDistanceMeters = route.optDouble("distance", 0.0),
            totalDurationSeconds = route.optDouble("duration", 0.0),
        )
    }

    private fun describeStep(type: String, modifier: String?, streetName: String): String {
        val streetSuffix = if (streetName.isNotBlank()) " onto $streetName" else ""
        return when (type) {
            "depart" -> "Head out" + if (streetName.isNotBlank()) " on $streetName" else ""
            "arrive" -> "Arrive at destination"
            "roundabout", "rotary", "roundabout turn" -> "Enter the roundabout"
            "exit roundabout", "exit rotary" -> "Exit the roundabout$streetSuffix"
            "turn", "end of road", "continue" -> when (modifier) {
                "left" -> "Turn left$streetSuffix"
                "right" -> "Turn right$streetSuffix"
                "sharp left" -> "Sharp left$streetSuffix"
                "sharp right" -> "Sharp right$streetSuffix"
                "slight left" -> "Slight left$streetSuffix"
                "slight right" -> "Slight right$streetSuffix"
                "uturn" -> "Make a U-turn"
                else -> "Continue$streetSuffix"
            }
            "merge" -> "Merge$streetSuffix"
            "fork" -> when (modifier) {
                "left" -> "Keep left$streetSuffix"
                "right" -> "Keep right$streetSuffix"
                else -> "Continue$streetSuffix"
            }
            else -> "Continue$streetSuffix"
        }
    }
}
