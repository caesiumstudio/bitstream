package com.caesiumstudio.bitstream.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class SiteRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSites(): List<SiteEntry> {
        val json = prefs.getString(KEY_SITES, "[]") ?: "[]"
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            SiteEntry(
                id = obj.getLong("id"),
                url = obj.getString("url"),
                displayName = obj.getString("displayName"),
                isFavorite = obj.optBoolean("isFavorite", false)
            )
        }
    }

    fun addSite(url: String): SiteEntry {
        val sites = loadSites().toMutableList()
        val normalized = normalizeUrl(url)
        val entry = SiteEntry(
            id = System.currentTimeMillis(),
            url = normalized,
            displayName = extractDisplayName(normalized)
        )
        sites.add(entry)
        saveSites(sites)
        return entry
    }

    fun updateSite(updated: SiteEntry) {
        val sites = loadSites().toMutableList()
        val idx = sites.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            sites[idx] = updated
            saveSites(sites)
        }
    }

    fun deleteSite(id: Long) {
        val sites = loadSites().filter { it.id != id }
        saveSites(sites)
    }

    private fun saveSites(sites: List<SiteEntry>) {
        val array = JSONArray()
        sites.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("url", entry.url)
                put("displayName", entry.displayName)
                put("isFavorite", entry.isFavorite)
            })
        }
        prefs.edit().putString(KEY_SITES, array.toString()).apply()
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "https://$url"
        }
    }

    private fun extractDisplayName(url: String): String {
        return try {
            val host = Uri.parse(url).host ?: return url
            val noWww = host.removePrefix("www.")
            val name = noWww.substringBefore(".")
            name.replaceFirstChar { it.uppercaseChar() }
        } catch (e: Exception) {
            url
        }
    }

    fun toggleFavorite(id: Long) {
        val sites = loadSites().toMutableList()
        val idx = sites.indexOfFirst { it.id == id }
        if (idx >= 0) {
            sites[idx] = sites[idx].copy(isFavorite = !sites[idx].isFavorite)
            saveSites(sites)
        }
    }

    /**
     * Fetches remote JSON and returns all sites immediately (no availability check).
     * Must be called from a background thread.
     */
    fun fetchRemote(configUrl: String): List<SiteEntry>? {
        val remoteSites = RemoteConfigFetcher.fetch(configUrl)
        if (remoteSites.isEmpty()) return null
        val existing = loadSites()
        val existingFavIds = existing.filter { it.isFavorite }.map { it.id }.toSet()
        val remoteIds = remoteSites.map { it.id }.toSet()
        // Keep user-added sites (IDs not from remote — timestamp-based, always > 0xFFFFFFFF)
        val userSites = existing.filter { it.id !in remoteIds }
        val remoteEntries = remoteSites.map { remote ->
            SiteEntry(
                id = remote.id,
                url = remote.url,
                displayName = remote.name,
                isFavorite = remote.id in existingFavIds
            )
        }.sortedByDescending { it.isFavorite }
        val entries = remoteEntries + userSites
        saveSites(entries)
        return entries
    }

    /**
     * Filters the given list to only reachable sites, saves and returns the result.
     * Must be called from a background thread.
     */
    fun filterAvailable(sites: List<SiteEntry>): List<SiteEntry> {
        val remoteIds = sites.map { it.id }.toSet()
        // Always keep user-added sites regardless of reachability check
        val userSites = loadSites().filter { it.id !in remoteIds }
        val available = sites.filter { it.isFavorite || SiteAvailabilityChecker.isReachable(it.url) }
        val entries = available + userSites
        saveSites(entries)
        return entries
    }

    /**
     * Fetches the remote config JSON, checks each site for availability,
     * then replaces the stored list with only the reachable remote sites.
     * Any site not present in the remote config is removed.
     *
     * Must be called from a background thread.
     *
     * @return the new list of sites after sync, or null if the fetch failed entirely.
     */
    fun syncFromRemote(configUrl: String): List<SiteEntry>? {
        val remoteSites = RemoteConfigFetcher.fetch(configUrl)
        if (remoteSites.isEmpty()) return null

        val available = remoteSites.filter { remote ->
            SiteAvailabilityChecker.isReachable(remote.url)
        }

        val entries = available.map { remote ->
            SiteEntry(
                id = remote.id,
                url = remote.url,
                displayName = remote.name
            )
        }

        saveSites(entries)
        return entries
    }

    companion object {
        private const val PREFS_NAME = "bitstream_sites"
        private const val KEY_SITES = "sites"
    }
}
