package com.caesiumstudio.pinstream.webview

import android.content.Context
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

object AdBlocker {

    private val blockedDomains = HashSet<String>(120000)
    @Volatile private var loaded = false

    // URL substring patterns — block any request whose full URL contains one of these
    private val blockedUrlPatterns = listOf(
        // Ad networks by path
        "/ads/", "/ad/", "/adserv", "/adserver", "/adservice",
        "/advertisement", "/advertisements", "/advert/",
        "/banner/", "/banners/", "/popup/", "/popups/",
        "/track/", "/tracker/", "/tracking/",
        "/pixel/", "/beacon/",
        "/sponsored/", "/promo/", "/promotion/",
        // Well-known ad/tracker domains (substring match catches CDN subdomains)
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "googletagmanager.com", "googletagservices.com",
        "google-analytics.com", "analytics.google.com",
        "adnxs.com", "adsrvr.org", "adform.net",
        "moatads.com", "scorecardresearch.com",
        "rubiconproject.com", "pubmatic.com", "openx.net",
        "taboola.com", "outbrain.com", "revcontent.com",
        "zergnet.com", "mgid.com", "criteo.com",
        "advertising.com", "adobedtm.com",
        "amazon-adsystem.com", "media.net",
        "yieldmanager.com", "yieldmo.com",
        "mopub.com", "chartboost.com", "applovin.com", "ironsrc.com",
        "vungle.com", "inmobi.com", "unity3d.com/ads",
        // Query param patterns that indicate tracking
        "?utm_", "&utm_", "?fbclid=", "&fbclid=",
        "?gclid=", "&gclid=", "?dclid=", "&dclid=",
    )

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        context.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim().lowercase()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    blockedDomains.add(trimmed)
                }
            }
        }
        loaded = true
    }

    fun shouldBlock(url: String): Boolean {
        if (!loaded) return false
        return try {
            val lower = url.lowercase()

            // 1. Check full-URL substring patterns (catches path-based and CDN ads)
            if (blockedUrlPatterns.any { lower.contains(it) }) return true

            // 2. Walk up the domain hierarchy: ads.example.com → example.com → com
            val host = android.net.Uri.parse(url).host?.lowercase() ?: return false
            val parts = host.split(".")
            for (i in 0 until parts.size - 1) {
                if (blockedDomains.contains(parts.drop(i).joinToString("."))) return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
