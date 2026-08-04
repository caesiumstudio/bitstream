package com.caesiumstudio.bitstream.data

import android.content.Context
import org.json.JSONArray

class RecentlyVisitedRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records a visit and returns the updated recent list. Keeps max [MAX] entries, most recent first. */
    fun recordVisit(site: SiteEntry) {
        val ids = loadIds().toMutableList()
        ids.remove(site.id)
        ids.add(0, site.id)
        if (ids.size > MAX) ids.subList(MAX, ids.size).clear()
        val array = JSONArray().also { arr -> ids.forEach { arr.put(it) } }
        prefs.edit().putString(KEY_IDS, array.toString()).apply()
    }

    /** Returns recently visited sites in order (most recent first), resolved against current site list. */
    fun loadRecents(allSites: List<SiteEntry>): List<SiteEntry> {
        val siteMap = allSites.associateBy { it.id }
        return loadIds().mapNotNull { siteMap[it] }
    }

    private fun loadIds(): List<Long> {
        val json = prefs.getString(KEY_IDS, "[]") ?: "[]"
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getLong(it) }
    }

    companion object {
        private const val PREFS_NAME = "bitstream_recents"
        private const val KEY_IDS = "recent_ids"
        private const val MAX = 10
    }
}
