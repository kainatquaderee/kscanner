package com.kscanner.data

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Owns the on-disk layout. Everything lives under app-private storage:
 *
 *   files/documents/[id]/original_[token].jpg   untouched capture
 *   files/documents/[id]/page_[token].jpg       rendered page after crop and filter
 *   files/exports/[name].pdf                    generated PDFs
 *   files/thumbs/[id].jpg                       library thumbnails
 *
 * Originals are retained, so reprocessing a page never compounds JPEG loss.
 */
class StorageManager(context: Context) {

    private val root: File = context.applicationContext.filesDir

    val documentsDir: File = File(root, "documents")
    val exportsDir: File = File(root, "exports")
    val thumbsDir: File = File(root, "thumbs")

    /** Scratch space for captures before they are committed to a document. */
    val capturesDir: File = File(context.applicationContext.cacheDir, "captures")

    fun documentDir(documentId: Long): File = File(documentsDir, documentId.toString()).apply { mkdirs() }

    fun newCaptureFile(): File {
        capturesDir.mkdirs()
        return File(capturesDir, "capture_" + UUID.randomUUID() + ".jpg")
    }

    fun newPageFile(documentId: Long, prefix: String): File =
        File(documentDir(documentId), prefix + "_" + UUID.randomUUID() + ".jpg")

    fun coverFile(documentId: Long): File = File(documentsDir, "cover_$documentId.jpg")

    fun thumbnailFile(documentId: Long): File {
        thumbsDir.mkdirs()
        return File(thumbsDir, "$documentId.jpg")
    }

    fun uniqueExportFile(baseName: String, extension: String): File {
        exportsDir.mkdirs()
        val safe = baseName.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "document" }
        var candidate = File(exportsDir, "$safe.$extension")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(exportsDir, "${safe}_$counter.$extension")
            counter++
        }
        return candidate
    }

    fun deleteDocument(documentId: Long) {
        File(documentsDir, documentId.toString()).deleteRecursively()
        thumbnailFile(documentId).delete()
    }

    fun documentSize(documentId: Long): Long {
        val dir = File(documentsDir, documentId.toString())
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun deleteFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    fun sweepCaptures(olderThanMillis: Long = 24L * 60 * 60 * 1000) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        capturesDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}
