package com.joseluixrax.gbaemu.ui.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.joseluixrax.gbaemu.emulator.RomLoader

data class RomEntry(val uri: Uri, val name: String, val size: Long)

/**
 * Lists ROMs inside a folder the user picked (Storage Access Framework).
 * Queries the provider directly: much faster than DocumentFile on big folders.
 */
object RomScanner {
    private const val MAX_DEPTH = 4

    fun scan(context: Context, treeUri: Uri): List<RomEntry> {
        val out = ArrayList<RomEntry>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        scanFolder(context, treeUri, rootId, 0, out)
        return out.sortedBy { it.name.lowercase() }
    }

    private fun scanFolder(context: Context, treeUri: Uri, folderId: String, depth: Int, out: MutableList<RomEntry>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val subFolders = ArrayList<String>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH && !name.startsWith(".")) subFolders.add(id)
                    } else if (RomLoader.isSupported(name)) {
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        out.add(RomEntry(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, size))
                    }
                }
            }
        } catch (_: Exception) {
            // Unreadable folder: skip it.
        }
        subFolders.forEach { scanFolder(context, treeUri, it, depth + 1, out) }
    }
}
