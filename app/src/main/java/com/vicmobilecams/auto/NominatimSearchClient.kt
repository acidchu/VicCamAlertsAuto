package com.vicmobilecams.auto

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchResult(val displayName: String, val lat: Double, val lon: Double)

/**
 * Forward-geocodes free-text destination queries via OpenStreetMap Nominatim -- the same service
 * (and the same usage policy: 1 request/second, descriptive User-Agent) the offline data pipeline
 * already relies on. Only ever called on explicit search submission (see DestinationSearchScreen),
 * never per keystroke, to stay within that policy.
 */
object NominatimSearchClient {
    private const val USER_AGENT =
        "VicCamAlertsAuto/1.0 (personal Android Auto project; contact bmsomething98@gmail.com)"

    fun search(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&limit=5&countrycodes=au"
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            parseResults(body)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseResults(body: String): List<SearchResult> {
        val array = JSONArray(body)
        val results = ArrayList<SearchResult>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            results.add(
                SearchResult(
                    displayName = obj.optString("display_name", "Unknown location"),
                    lat = obj.getDouble("lat"),
                    lon = obj.getDouble("lon"),
                )
            )
        }
        return results
    }
}
