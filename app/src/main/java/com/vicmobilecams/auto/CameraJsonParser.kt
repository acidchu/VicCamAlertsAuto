package com.vicmobilecams.auto

import org.json.JSONArray

/** Parses the cameras.json array format shared by the bundled asset and the daily remote download. */
object CameraJsonParser {
    fun parse(json: String): List<Camera> {
        val array = JSONArray(json)
        val cameras = ArrayList<Camera>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            cameras.add(
                Camera(
                    location = obj.getString("location"),
                    suburb = obj.getString("suburb"),
                    lat = obj.getDouble("lat"),
                    lon = obj.getDouble("lon"),
                    reasonCode = obj.optString("reasonCode", ""),
                    auditDate = obj.optString("auditDate", ""),
                )
            )
        }
        return cameras
    }
}
