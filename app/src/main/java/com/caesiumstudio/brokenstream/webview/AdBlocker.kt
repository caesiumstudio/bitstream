package com.caesiumstudio.pinstream.webview

import android.content.Context
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

object AdBlocker {

    private val blockedDomains = HashSet<String>(120000)
    @Volatile private var loaded = false

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
            val host = android.net.Uri.parse(url).host?.lowercase() ?: return false
            // Walk up the domain hierarchy: ads.example.com → example.com → com
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
