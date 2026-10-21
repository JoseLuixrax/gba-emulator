package com.joseluixrax.gbaemu.emulator

import android.content.Context
import java.io.File

/**
 * Where everything belonging to one game lives.
 *
 * Files go to the app's external files dir (Android/data/<package>/files) so
 * they can be copied over USB. Layout:
 *   saves/<game>.sav            battery save, same format as My Boy / VBA / mGBA
 *   states/<game>/slotN.state   save states (slot 0 = automatic)
 *   states/<game>/slotN.png     their thumbnails
 */
class GameFiles(context: Context, val gameKey: String) {
    private val root: File = rootDir(context)

    val saveFile = File(root, "saves/$gameKey.sav")
    val stateDir = File(root, "states/$gameKey")

    fun stateFile(slot: Int) = File(stateDir, "slot$slot.state")
    fun thumbnailFile(slot: Int) = File(stateDir, "slot$slot.png")

    fun ensureDirs() {
        saveFile.parentFile?.mkdirs()
        stateDir.mkdirs()
    }

    fun deleteStates() {
        stateDir.deleteRecursively()
    }

    companion object {
        const val AUTO_SLOT = 0
        const val SLOT_COUNT = 10

        fun rootDir(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

        fun biosFile(context: Context) = File(context.filesDir, "bios/gba_bios.bin")

        /** "Pokémon Esmeralda (S).gba" -> "Pokémon Esmeralda (S)". */
        fun keyFor(fileName: String): String {
            val base = fileName.substringAfterLast('/').let {
                val dot = it.lastIndexOf('.')
                if (dot > 0) it.substring(0, dot) else it
            }
            return base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().ifEmpty { "game" }
        }
    }
}
