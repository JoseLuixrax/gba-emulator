package com.joseluixrax.gbaemu.ui.game

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.joseluixrax.gbaemu.R
import java.io.File
import java.io.FileOutputStream

/** Pictures the game screens share: gallery screenshots and state thumbnails. */
object Screenshots {
    /** Saves to Pictures/<app name>. Returns a human readable location, or null on failure. */
    fun saveToGallery(context: Context, bitmap: Bitmap, name: String): String? = try {
        val folder = Environment.DIRECTORY_PICTURES + "/" + context.getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            }
            val uri: Uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            folder
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "screenshots").apply { mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.path
        }
    } catch (_: Exception) {
        null
    }

    fun writePng(bitmap: Bitmap, file: File) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
        }
    }
}
