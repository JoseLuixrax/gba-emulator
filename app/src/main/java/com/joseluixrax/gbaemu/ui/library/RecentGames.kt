package com.joseluixrax.gbaemu.ui.library

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** The last games played, shown at the top of the library. */
object RecentGames {
    private const val KEY = "recent_games"
    private const val MAX = 8

    data class Entry(val uri: Uri, val name: String, val playedAt: Long)

    fun list(context: Context): List<Entry> {
        val json = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Entry(Uri.parse(o.getString("uri")), o.getString("name"), o.optLong("at"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, uri: Uri, name: String) {
        val entries = list(context).filter { it.uri != uri }.toMutableList()
        entries.add(0, Entry(uri, name, System.currentTimeMillis()))
        save(context, entries.take(MAX))
    }

    fun remove(context: Context, uri: Uri) {
        save(context, list(context).filter { it.uri != uri })
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("at", it.playedAt)) }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, array.toString()).apply()
    }
}
