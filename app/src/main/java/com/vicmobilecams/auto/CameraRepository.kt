package com.vicmobilecams.auto

import android.content.Context
import java.io.File

/**
 * Serves camera locations, preferring the most recent successful download from
 * CamerasUpdateWorker over the dataset bundled into the app at build time. The bundled asset
 * guarantees the app works offline immediately after install, before the first daily sync
 * completes.
 */
object CameraRepository {
    private var cache: List<Camera>? = null
    private var cachedRemoteModified: Long = -1L

    fun loadAll(context: Context): List<Camera> {
        val remoteFile = File(context.filesDir, CamerasUpdateWorker.REMOTE_FILE_NAME)
        val remoteModified = if (remoteFile.exists()) remoteFile.lastModified() else 0L

        cache?.let { if (cachedRemoteModified == remoteModified) return it }

        val cameras = loadFromRemote(remoteFile) ?: loadFromBundledAsset(context)
        cache = cameras
        cachedRemoteModified = remoteModified
        return cameras
    }

    fun nearest(context: Context, fromLat: Double, fromLon: Double, limit: Int): List<Camera> {
        return loadAll(context)
            .sortedBy { it.distanceKmFrom(fromLat, fromLon) }
            .take(limit)
    }

    private fun loadFromRemote(remoteFile: File): List<Camera>? {
        if (!remoteFile.exists()) return null
        return try {
            val json = remoteFile.readText()
            CameraJsonParser.parse(json).ifEmpty { null }
        } catch (_: Exception) {
            null // corrupt/partial download -- fall back to the bundled asset
        }
    }

    private fun loadFromBundledAsset(context: Context): List<Camera> {
        val json = context.assets.open("cameras.json").bufferedReader().use { it.readText() }
        return CameraJsonParser.parse(json)
    }
}
