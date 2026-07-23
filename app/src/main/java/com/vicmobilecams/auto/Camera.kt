package com.vicmobilecams.auto

data class Camera(
    val location: String,
    val suburb: String,
    val lat: Double,
    val lon: Double,
    val reasonCode: String,
    val auditDate: String,
) {
    /** Great-circle distance to (fromLat, fromLon) in kilometres. */
    fun distanceKmFrom(fromLat: Double, fromLon: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat - fromLat)
        val dLon = Math.toRadians(lon - fromLon)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(lat)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadiusKm * c
    }
}
