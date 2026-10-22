package com.joseluixrax.gbaemu.storage

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.joseluixrax.gbaemu.R
import com.joseluixrax.gbaemu.emulator.GameFiles
import java.io.File
import java.io.FileNotFoundException

/**
 * Shows the app's own files (saves/, states/...) in the system "Files" app and
 * in every file picker, under the name "AdvanceBoy".
 *
 * Since Android 11 file managers can't open Android/data, so this is how the
 * user reaches their .sav files without a PC.
 *
 * Document ids are "root" for the base folder and "root/<relative path>" below it.
 */
class GameFilesProvider : DocumentsProvider() {

    private lateinit var baseDir: File

    override fun onCreate(): Boolean {
        baseDir = GameFiles.rootDir(context!!)
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, context!!.getString(R.string.files_root_summary))
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, baseDir.freeSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addRow(cursor, documentId, fileFor(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = fileFor(parentDocumentId)
        parent.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { addRow(cursor, "$parentDocumentId/${it.name}", it) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        val safeName = displayName.replace('/', '_')
        var target = File(parent, safeName)
        var counter = 1
        while (target.exists()) {
            val dot = safeName.lastIndexOf('.')
            target = if (dot > 0) {
                File(parent, "${safeName.substring(0, dot)} ($counter)${safeName.substring(dot)}")
            } else {
                File(parent, "$safeName ($counter)")
            }
            counter++
        }
        val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
        if (!created) throw FileNotFoundException("Cannot create $displayName")
        return "$parentDocumentId/${target.name}"
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT_ID) throw FileNotFoundException("The root folder can't be deleted")
        if (!fileFor(documentId).deleteRecursively()) throw FileNotFoundException("Cannot delete $documentId")
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/")

    override fun getDocumentType(documentId: String): String = mimeTypeOf(fileFor(documentId))

    private fun addRow(cursor: MatrixCursor, documentId: String, file: File) {
        var flags = 0
        if (file.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (documentId != ROOT_ID) flags = flags or Document.FLAG_SUPPORTS_DELETE
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (documentId == ROOT_ID) context!!.getString(R.string.app_name) else file.name)
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    /** Maps a document id to a file, refusing anything outside the base folder. */
    private fun fileFor(documentId: String): File {
        if (documentId != ROOT_ID && !documentId.startsWith("$ROOT_ID/")) {
            throw FileNotFoundException("Unknown document $documentId")
        }
        val relative = documentId.removePrefix(ROOT_ID).removePrefix("/")
        val file = if (relative.isEmpty()) baseDir else File(baseDir, relative)
        val base = baseDir.canonicalPath
        val path = file.canonicalPath
        if (path != base && !path.startsWith(base + File.separator)) {
            throw FileNotFoundException("Outside of the app folder: $documentId")
        }
        if (!file.exists()) throw FileNotFoundException("Not found: $documentId")
        return file
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        const val ROOT_ID = "root"
        const val SAVES_ID = "root/saves"

        fun authority(context: Context) = "${context.packageName}.files"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
