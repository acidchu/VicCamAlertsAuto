package com.vicmobilecams.auto

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the daily-published camera dataset -- regenerated from vic.gov.au's monthly
 * spreadsheet by the update-cameras.yml GitHub Actions workflow -- and stores it for
 * CameraRepository to pick up. vic.gov.au itself only republishes monthly, so most runs fetch
 * data identical to what's already cached; we still overwrite each time so corrections picked
 * up by the workflow reach the app promptly.
 */
class CamerasUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val connection = URL(CAMERAS_JSON_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "VicCamAlertsAuto/1.0 (Android)")

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            // Validate before persisting so a truncated/corrupt download never clobbers a good cache.
            val cameras = CameraJsonParser.parse(json)
            if (cameras.isEmpty()) return@withContext Result.retry()

            val target = File(applicationContext.filesDir, REMOTE_FILE_NAME)
            val tmp = File(applicationContext.filesDir, "$REMOTE_FILE_NAME.tmp")
            tmp.writeText(json)
            tmp.renameTo(target)

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val REMOTE_FILE_NAME = "cameras_remote.json"
        private const val CAMERAS_JSON_URL =
            "https://raw.githubusercontent.com/acidchu/VicCamAlertsAuto/main/data/cameras.json"
    }
}
