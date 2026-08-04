package com.caesiumstudio.bitstream.webview

import android.content.Context
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest

object AdBlocker {

    @Volatile private var loaded = false

    // Bloom filter state — populated from blocklist.bin
    private var bits: ByteArray = ByteArray(0)
    private var m: Int = 0  // bit array size
    private var k: Int = 0  // number of hash functions

    // URL substring patterns — fast pre-filter before domain lookup
    private val blockedUrlPatterns = listOf(
        "/ads/", "/ad/", "/adserv", "/adserver", "/adservice",
        "/advertisement", "/advertisements", "/advert/",
        "/banner/", "/banners/", "/popup/", "/popups/",
        "/track/", "/tracker/", "/tracking/",
        "/pixel/", "/beacon/",
        "/sponsored/", "/promo/", "/promotion/",
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "googletagservices.com", "google-analytics.com",
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
        "?utm_", "&utm_", "?fbclid=", "&fbclid=",
        "?gclid=", "&gclid=", "?dclid=", "&dclid=",
    )

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        try {
            context.assets.open("blocklist.bin").use { input ->
                val dis = DataInputStream(input)
                m = dis.readInt()
                k = dis.readInt()
                val byteCount = (m + 7) / 8
                bits = ByteArray(byteCount)
                dis.readFully(bits)
            }
            loaded = true
        } catch (e: Exception) {
            // Fall back gracefully — pattern list still works
            loaded = true
        }
    }

    fun shouldBlock(url: String): Boolean {
        if (!loaded) return false
        return try {
            val lower = url.lowercase()

            // 1. Fast substring pattern check
            if (blockedUrlPatterns.any { lower.contains(it) }) return true

            // 2. Bloom filter domain check — walk up hierarchy
            if (m > 0) {
                val host = android.net.Uri.parse(url).host?.lowercase() ?: return false
                val parts = host.split(".")
                for (i in 0 until parts.size - 1) {
                    val domain = parts.drop(i).joinToString(".")
                    if (mightContain(domain)) return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun mightContain(domain: String): Boolean {
        val b = domain.toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5").digest(b)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(b)
        val h1 = md5.toLong()
        val h2 = sha1.toLong()
        for (i in 0 until k) {
            val pos = ((h1 + i.toLong() * h2) % m.toLong()).let {
                if (it < 0) it + m.toLong() else it
            }.toInt()
            if (bits[pos shr 3].toInt() and (1 shl (pos and 7)) == 0) return false
        }
        return true
    }

    private fun ByteArray.toLong(): Long {
        var result = 0L
        for (i in 0 until minOf(size, 8)) {
            result = result or ((this[i].toLong() and 0xFF) shl (i * 8))
        }
        return result
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
