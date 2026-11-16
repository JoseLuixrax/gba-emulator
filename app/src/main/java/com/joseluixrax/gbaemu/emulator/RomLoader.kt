package com.joseluixrax.gbaemu.emulator

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads ROM files (plain or inside a .zip) through Android's content providers. */
object RomLoader {
    val GBA_EXTENSIONS = setOf("gba", "agb", "gb", "gbc", "sgb")
    val DS_EXTENSIONS = setOf("nds")
    val ROM_EXTENSIONS = GBA_EXTENSIONS + DS_EXTENSIONS
    val SUPPORTED_EXTENSIONS = ROM_EXTENSIONS + "zip"

    /** 32 MiB is the largest GBA cartridge; leave room for odd dumps. */
    private const val MAX_ROM_SIZE = 64L * 1024 * 1024

    /** DS cartridges go up to 512 MiB. */
    private const val MAX_DS_ROM_SIZE = 512L * 1024 * 1024

    sealed class Rom(val innerName: String) {
        /** GBA / GB / GBC: small enough to hand over as bytes. */
        class Gba(val data: ByteArray, innerName: String) : Rom(innerName)

        /**
         * DS: can be hundreds of MB, so the native core reads it from a file
         * descriptor instead. Call [cleanUp] once it has been loaded.
         */
        class Nds(innerName: String, private val opener: () -> ParcelFileDescriptor, private val tempFile: File?) :
            Rom(innerName) {
            fun open(): ParcelFileDescriptor = opener()

            fun cleanUp() {
                tempFile?.delete()
            }
        }
    }

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
        val extension = extensionOf(displayName)
        if (extension in DS_EXTENSIONS) return openDs(context, uri, displayName)

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open $uri")
        input.use { stream ->
            if (extension == "zip") {
                ZipInputStream(BufferedInputStream(stream)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/')
                        if (entry.isDirectory) continue
                        when (extensionOf(name)) {
                            in GBA_EXTENSIONS -> return Rom.Gba(readLimited(zip, MAX_ROM_SIZE), name)
                            in DS_EXTENSIONS -> {
                                val file = tempDsFile(context)
                                copyLimited(zip, file)
                                return Rom.Nds(name, { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }, file)
                            }
                        }
                    }
                }
                throw IOException("The zip file doesn't contain a ROM")
            }
            return Rom.Gba(readLimited(stream, MAX_ROM_SIZE), displayName)
        }
    }

    /** A .nds file is read in place when the provider allows it, copied otherwise. */
    private fun openDs(context: Context, uri: Uri, displayName: String): Rom.Nds {
        val readable = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
        if (readable) {
            return Rom.Nds(displayName, {
                context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
            }, null)
        }
        val file = tempDsFile(context)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { copyLimited(it, file) }
        return Rom.Nds(displayName, { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }, file)
    }

    private fun tempDsFile(context: Context) = File(context.cacheDir, "rom.nds").also { it.delete() }

    private fun copyLimited(stream: InputStream, file: File) {
        try {
            file.outputStream().use { out ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DS_ROM_SIZE) throw IOException("File is too large to be a ROM")
                    out.write(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
    }

    private fun readLimited(stream: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(16 * 1024 * 1024)
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("File is too large to be a ROM")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
