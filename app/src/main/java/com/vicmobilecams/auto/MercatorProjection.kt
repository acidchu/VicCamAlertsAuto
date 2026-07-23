package com.vicmobilecams.auto

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Standard Web Mercator ("slippy map") tile math -- https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames */
object MercatorProjection {
    const val TILE_SIZE = 256.0
    private const val MAX_LATITUDE = 85.05112878

    fun worldPixelsPerTile(zoom: Int): Double = TILE_SIZE * 2.0.pow(zoom)

    /** (lat, lon) -> pixel coordinates in the full "world" bitmap at the given integer zoom. */
    fun toWorldPixel(lat: Double, lon: Double, zoom: Int): DoubleArray {
        val scale = worldPixelsPerTile(zoom)
        val clampedLat = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val x = (lon + 180.0) / 360.0 * scale
        val latRad = Math.toRadians(clampedLat)
        val y = (1.0 - asinh(tan(latRad)) / PI) / 2.0 * scale
        return doubleArrayOf(x, y)
    }

    /** Inverse of [toWorldPixel]. */
    fun fromWorldPixel(x: Double, y: Double, zoom: Int): DoubleArray {
        val scale = worldPixelsPerTile(zoom)
        val lon = x / scale * 360.0 - 180.0
        val n = PI - 2.0 * PI * y / scale
        val lat = Math.toDegrees(atan(sinh(n)))
        return doubleArrayOf(lat, lon)
    }
}
