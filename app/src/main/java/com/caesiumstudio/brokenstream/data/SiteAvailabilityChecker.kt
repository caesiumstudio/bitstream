package com.caesiumstudio.pinstream.data

import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks whether a given URL is reachable by performing a HEAD request.
 * Returns true if the server responds with any HTTP response (even redirects),
 * false if the connection times out or throws an exception.
 */
object SiteAvailabilityChecker {

    fun isReachable(siteUrl: String): Boolean {
        return try {
            val conn = URL(siteUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
            )
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499  // treat 2xx/3xx/4xx as "up" — 5xx or timeout = down
        } catch (_: Exception) {
            false
        }
    }
}
