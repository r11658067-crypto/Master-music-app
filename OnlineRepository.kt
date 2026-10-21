package com.mastermusic.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Online search over Jamendo, a library of free, legally shareable (Creative Commons) music.
 *
 * Get a free client ID at https://developer.jamendo.com and paste it below.
 * Note: Jamendo's free API is for non-commercial apps; for a commercial/Play Store
 * release with ads or payments, check Jamendo's licensing terms.
 */
object OnlineRepository {
    const val CLIENT_ID = "YOUR_JAMENDO_CLIENT_ID"

    fun enabled() = CLIENT_ID != "YOUR_JAMENDO_CLIENT_ID"

    fun search(query: String): List<Song> {
        val url = "https://api.jamendo.com/v3.0/tracks/?client_id=$CLIENT_ID&format=json&limit=30" +
            "&audioformat=mp32&search=${URLEncoder.encode(query, "UTF-8")}"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(text).getJSONArray("results")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val dl = if (o.optBoolean("audiodownload_allowed")) o.optString("audiodownload").takeIf { it.isNotBlank() } else null
                Song(
                    id = "j_" + o.getString("id"),
                    title = o.getString("name"),
                    artist = o.optString("artist_name"),
                    uri = o.getString("audio"),
                    artUri = o.optString("image").takeIf { it.isNotBlank() },
                    durationMs = o.optLong("duration") * 1000,
                    isLocal = false,
                    remoteUrl = dl,
                )
            }
        } finally {
            conn.disconnect()
        }
    }
}
