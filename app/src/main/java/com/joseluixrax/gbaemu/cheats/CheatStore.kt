package com.joseluixrax.gbaemu.cheats

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One cheat as the user typed it. [code] may span several lines. */
data class Cheat(
    var name: String,
    var code: String,
    var type: Int = CheatType.AUTO,
    var enabled: Boolean = true,
)

/** Values understood by the native core (see EmuCheatType in emu_core.h). */
object CheatType {
    const val AUTO = 0
    const val CODEBREAKER = 1
    const val GAMESHARK = 2
    const val ACTION_REPLAY = 3
    const val RAW = 4
    const val GAME_GENIE = 5
}

/** Cheats are stored per game as JSON in files/cheats/<game>.json. */
class CheatStore(context: Context, gameKey: String) {
    private val file = File(context.filesDir, "cheats/$gameKey.json")

    fun load(): MutableList<Cheat> {
        if (!file.isFile) return mutableListOf()
        return try {
            val array = JSONArray(file.readText())
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                Cheat(
                    name = o.optString("name"),
                    code = o.optString("code"),
                    type = o.optInt("type", CheatType.AUTO),
                    enabled = o.optBoolean("enabled", true),
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(cheats: List<Cheat>) {
        val array = JSONArray()
        cheats.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("code", it.code)
                    .put("type", it.type)
                    .put("enabled", it.enabled),
            )
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString(2))
        tmp.renameTo(file)
    }

    /** Last modification time, so the game can tell when to re-apply cheats. */
    fun lastModified(): Long = if (file.isFile) file.lastModified() else 0L
}
