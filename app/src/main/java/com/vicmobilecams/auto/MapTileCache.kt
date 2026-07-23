package com.vicmobilecams.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Fetches and caches OpenStreetMap raster tiles for the Android Auto navigation map. Mirrors the
 * low-volume, well-identified usage the phone map (osmdroid, see MapActivity) already makes
 * against the same tile servers, per OSM's tile usage policy:
 * https://operations.osmfoundation.org/policies/tiles/
 */
class MapTileCache(context: Context) {

    fun interface TileListener {
        fun onTileLoaded(zoom: Int, tileX: Int, tileY: Int)
    }

    private val memoryCache = LruCache<String, Bitmap>(MEMORY_CACHE_TILE_COUNT)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(4)
    private val diskCacheDir = File(context.cacheDir, "osm_tiles")

    fun getCachedTile(zoom: Int, tileX: Int, tileY: Int): Bitmap? {
        val key = tileKey(zoom, tileX, tileY)
        memoryCache.get(key)?.let { return it }

        val diskFile = tileFile(zoom, tileX, tileY)
        if (diskFile.exists()) {
            BitmapFactory.decodeFile(diskFile.path)?.let {
                memoryCache.put(key, it)
                return it
            }
        }
        return null
    }

    /** Fetches a tile in the background and calls [listener] on that background thread once ready. */
    fun requestTile(zoom: Int, tileX: Int, tileY: Int, listener: TileListener) {
        val key = tileKey(zoom, tileX, tileY)
        if (memoryCache.get(key) != null || !inFlight.add(key)) return

        executor.execute {
            try {
                val bitmap = downloadTile(zoom, tileX, tileY)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                    listener.onTileLoaded(zoom, tileX, tileY)
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun downloadTile(zoom: Int, tileX: Int, tileY: Int): Bitmap? {
        val diskFile = tileFile(zoom, tileX, tileY)
        if (diskFile.exists()) {
            return BitmapFactory.decodeFile(diskFile.path)
        }

        return try {
            val connection = URL("https://tile.openstreetmap.org/$zoom/$tileX/$tileY.png")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            diskFile.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                diskFile.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            BitmapFactory.decodeFile(diskFile.path)
        } catch (_: Exception) {
            null
        }
    }

    private fun tileKey(zoom: Int, tileX: Int, tileY: Int) = "$zoom/$tileX/$tileY"

    private fun tileFile(zoom: Int, tileX: Int, tileY: Int) = File(diskCacheDir, "$zoom/$tileX/$tileY.png")

    companion object {
        private const val MEMORY_CACHE_TILE_COUNT = 80
        private const val USER_AGENT =
            "VicCamAlertsAuto/1.0 (personal Android Auto project; contact bmsomething98@gmail.com)"
    }
}
