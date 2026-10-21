package com.joseluixrax.gbaemu.emulator

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Reads ROM files (plain or inside a .zip) through Android's content providers. */
object RomLoader {
    val ROM_EXTENSIONS = setOf("gba", "agb", "gb", "gbc", "sgb")
    val SUPPORTED_EXTENSIONS = ROM_EXTENSIONS + "zip"

    /** 32 MiB is the largest GBA cartridge; leave room for odd dumps. */
    private const val MAX_ROM_SIZE = 64L * 1024 * 1024

    class Rom(val data: ByteArray, val innerName: String)

    fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isSupported(name: String) = extensionOf(name) in SUPPORTED_EXTENSIONS

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "game.gba"
    }

    @Throws(IOException::class)
    fun load(context: Context, uri: Uri, displayName: String): Rom {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open $uri")
        input.use { stream ->
            if (extensionOf(displayName) == "zip") {
                ZipInputStream(BufferedInputStream(stream)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && extensionOf(entry.name) in ROM_EXTENSIONS) {
                            return Rom(readLimited(zip), entry.name.substringAfterLast('/'))
                        }
                    }
                }
                throw IOException("The zip file doesn't contain a GBA/GB/GBC ROM")
            }
            return Rom(readLimited(stream), displayName)
        }
    }

    private fun readLimited(stream: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(16 * 1024 * 1024)
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ROM_SIZE) throw IOException("File is too large to be a ROM")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
