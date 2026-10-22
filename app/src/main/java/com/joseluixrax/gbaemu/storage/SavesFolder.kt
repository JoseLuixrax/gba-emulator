package com.joseluixrax.gbaemu.storage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joseluixrax.gbaemu.R
import com.joseluixrax.gbaemu.emulator.GameFiles
import java.io.File

/** Opens the app's saves folder in the system "Files" app (see [GameFilesProvider]). */
object SavesFolder {
    // The Files app ships under one of these packages depending on the phone.
    private val FILES_APPS = listOf("com.google.android.documentsui", "com.android.documentsui")

    fun open(context: Context) {
        File(GameFiles.rootDir(context), "saves").mkdirs()
        val authority = GameFilesProvider.authority(context)
        val rootUri = DocumentsContract.buildRootUri(authority, GameFilesProvider.ROOT_ID)
        val savesUri = DocumentsContract.buildDocumentUri(authority, GameFilesProvider.SAVES_ID)

        val attempts = buildList {
            // Straight into saves/, then the app's root folder; any Files app first,
            // then the system one by package for phones whose default can't do it.
            add(view(savesUri, DocumentsContract.Document.MIME_TYPE_DIR, null))
            add(view(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM, null))
            for (pkg in FILES_APPS) {
                add(view(savesUri, DocumentsContract.Document.MIME_TYPE_DIR, pkg))
                add(view(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM, pkg))
            }
        }
        for (intent in attempts) {
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        showPath(context)
    }

    private fun view(uri: Uri, mimeType: String, pkg: String?) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        .apply { if (pkg != null) setPackage(pkg) }

    /** Last resort: explain where the files are and how to reach them. */
    private fun showPath(context: Context) {
        val path = File(GameFiles.rootDir(context), "saves").path
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saves_folder)
            .setMessage(context.getString(R.string.saves_folder_manual, path))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
