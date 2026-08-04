package com.caesiumstudio.bitstream.data

import android.net.Uri
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the remote sites JSON config from a public URL.
 * Expected JSON format: a plain array of URL strings.
 * [ "https://example.com", "https://another.com", ... ]
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
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val url = array.getString(i).trim()
            if (url.isEmpty()) return@mapNotNull null
            val host = Uri.parse(url).host?.removePrefix("www.") ?: return@mapNotNull null
            val name = host.substringBefore(".").replaceFirstChar { it.uppercaseChar() }
            RemoteSite(
                id = url.hashCode().toLong() and 0xFFFFFFFFL,
                name = name,
                url = url
            )
        }
    }
}
