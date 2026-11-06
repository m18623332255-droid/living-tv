package cn.living.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Store(private val context: Context) {
    val prefs = context.getSharedPreferences("livingtv", Context.MODE_PRIVATE)
    fun string(key: String, default: String = "") = prefs.getString(key, default) ?: default
    fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun sources(): List<Source> = try { val a = JSONArray(string("sources", "[]")); (0 until a.length()).map { Source.from(a.getJSONObject(it)) } } catch (_: Exception) { emptyList() }
    fun saveSources(items: List<Source>) = put("sources", sourceJson(items))

    /** Seed once, keeping existing URLs and user choices, including disabled sources. */
    fun installStarterSources() {
        installSources("starter_sources_v1", "starter-sources.json")
        installSources("starter_vod_v1", "starter-vod.json")
        installSources("starter_cn_v1", "starter-cn.json")
        installSources("starter_expansion_v1", "starter-expansion.json")
    }

    private fun installSources(flag: String, asset: String) {
        if (prefs.getBoolean(flag, false)) return
        val starter = context.assets.open(asset).bufferedReader().use {
            JSONArray(it.readText())
        }
        val merged = sources().toMutableList()
        val urls = merged.mapTo(mutableSetOf()) { it.url }
        for (index in 0 until starter.length()) {
            val source = Source.from(starter.getJSONObject(index))
            if (urls.add(source.url)) merged.add(source)
        }
        prefs.edit().putString("sources", sourceJson(merged)).putBoolean(flag, true).apply()
    }

    private fun sourceJson(items: List<Source>) =
        JSONArray().also { array -> items.forEach { array.put(it.json()) } }.toString()

    fun set(key: String): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    fun saveSet(key: String, items: Set<String>) { prefs.edit().putStringSet(key, items).apply() }
    fun toggle(key: String, value: String) { val s = set(key).toMutableSet(); if (!s.remove(value)) s.add(value); saveSet(key, s) }
    fun order(key: String): List<String> = try { val a = JSONArray(string(key, "[]")); (0 until a.length()).map { a.getString(it) } } catch (_: Exception) { emptyList() }
    fun saveOrder(key: String, items: List<String>) = put(key, JSONArray(items).toString())
    fun savedVod(): List<MediaEntry> = runCatching {
        CatalogParser.collection(string("saved_vod", "[]"), savedPlayback = true)
    }.getOrDefault(emptyList())

    fun isSaved(entry: MediaEntry): Boolean =
        savedVod().any { it.episodes.first().url == entry.episodes.first().url }

    fun toggleSaved(entry: MediaEntry): Boolean {
        val current = savedVod()
        val key = entry.episodes.first().url
        val remaining = current.filterNot { it.episodes.first().url == key }
        val added = remaining.size == current.size
        val updated = if (added) listOf(entry) + remaining else remaining
        put("saved_vod", JSONArray().also { array -> updated.forEach { array.put(it.json()) } }.toString())
        return added
    }

    fun queueEntry(): MediaEntry? = runCatching {
        CatalogParser.collection(string("queue_entry", "[]"), savedPlayback = true).firstOrNull()
    }.getOrNull()

    fun wasLive(url: String): Boolean = prefs.getBoolean("live:$url", false)

    fun history(): List<Episode> = try { val a = JSONArray(string("history", "[]")); (0 until a.length()).map { val o = a.getJSONObject(it); Episode(o.getString("name"), o.getString("url")) } } catch (_: Exception) { emptyList() }
    fun record(e: Episode, position: Long, live: Boolean) {
        prefs.edit().putBoolean("live:${e.url}", live).apply()
        if (!live && position > 1000) prefs.edit().putLong("position:${e.url}", position).apply()
        val items = (listOf(e) + history().filter { it.url != e.url }).take(60)
        put("history", JSONArray().also { a -> items.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url)) } }.toString())
    }
}
