package com.caesiumstudio.pinstream.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the remote sites JSON config from a public URL.
 * Expected JSON format:
 * {
 *   "version": 1,
 *   "sites": [
 *     { "id": 1, "name": "SiteName", "url": "https://example.com" },
 *     ...
 *   ]
 * }
 */
object RemoteConfigFetcher {

    data class RemoteSite(val id: Long, val name: String, val url: String)

    fun fetch(configUrl: String): List<RemoteSite> {
        val conn = URL(configUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Accept", "application/json")
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return emptyList()

            val body = conn.inputStream.bufferedReader().readText()
            parseJson(body)
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private fun parseJson(json: String): List<RemoteSite> {
        val root = JSONObject(json)
        val array = root.getJSONArray("sites")
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            RemoteSite(
                id = obj.getLong("id"),
                name = obj.getString("name"),
                url = obj.getString("url")
            )
        }
    }
}
