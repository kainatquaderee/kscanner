package com.kscanner.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Synchronous SQLite access for documents and pages. Callers are responsible for
 * invoking these off the main thread.
 */
class DocumentStore(context: Context) {

    private val helper = DbHelper(context.applicationContext)

    private fun db(): SQLiteDatabase = helper.writableDatabase

    // ------------------------------------------------------------ documents

    fun insertDocument(name: String, folder: String?, now: Long = System.currentTimeMillis()): Long {
        val values = ContentValues().apply {
            put("name", name)
            put("folder", folder)
            put("created_at", now)
            put("updated_at", now)
            put("size_bytes", 0L)
        }
        return db().insert("documents", null, values)
    }

    fun renameDocument(id: Long, name: String) {
        val values = ContentValues().apply {
            put("name", name)
            put("updated_at", System.currentTimeMillis())
        }
        db().update("documents", values, "id = ?", arrayOf(id.toString()))
    }

    fun moveDocument(id: Long, folder: String?) {
        val values = ContentValues().apply {
            put("folder", folder)
            put("updated_at", System.currentTimeMillis())
        }
        db().update("documents", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteDocument(id: Long) {
        db().delete("documents", "id = ?", arrayOf(id.toString()))
    }

    fun getDocument(id: Long): Document? =
        db().rawQuery("$DOCUMENT_SELECT WHERE d.id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.toDocument() else null
        }

    fun listDocuments(
        query: String? = null,
        folder: String? = null,
        sort: DocumentSort = DocumentSort.DATE_DESC,
    ): List<Document> {
        val conditions = ArrayList<String>()
        val args = ArrayList<String>()

        if (!query.isNullOrBlank()) {
            conditions.add(
                "(d.name LIKE ? COLLATE NOCASE OR EXISTS (" +
                    "SELECT 1 FROM pages p WHERE p.document_id = d.id AND p.ocr_text LIKE ? COLLATE NOCASE))",
            )
            val like = "%${query.trim()}%"
            args.add(like)
            args.add(like)
        }
        if (folder != null) {
            conditions.add("d.folder = ?")
            args.add(folder)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val sql = DOCUMENT_SELECT + where + " ORDER BY " + sort.clause()

        return db().rawQuery(sql, args.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toDocument())
            }
        }
    }

    fun listFolders(): List<String> =
        db().rawQuery(
            "SELECT DISTINCT folder FROM documents WHERE folder IS NOT NULL AND folder <> '' ORDER BY folder COLLATE NOCASE",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.text("folder"))
            }
        }

    fun countDocuments(): Int =
        db().rawQuery("SELECT COUNT(*) AS c FROM documents", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.int("c") else 0
        }

    /** Updates the cached columns shown in the library list and marks the document changed. */
    fun refreshDocumentStats(id: Long, coverPath: String?, sizeBytes: Long) {
        val values = ContentValues().apply {
            put("cover_path", coverPath)
            put("size_bytes", sizeBytes)
            put("updated_at", System.currentTimeMillis())
        }
        db().update("documents", values, "id = ?", arrayOf(id.toString()))
    }

    fun touchDocument(id: Long) {
        val values = ContentValues().apply { put("updated_at", System.currentTimeMillis()) }
        db().update("documents", values, "id = ?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------ pages

    fun insertPage(
        documentId: Long,
        index: Int,
        originalPath: String,
        processedPath: String,
        filterId: Int,
        rotation: Int,
        corners: String?,
        width: Int,
        height: Int,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val values = ContentValues().apply {
            put("document_id", documentId)
            put("page_index", index)
            put("original_path", originalPath)
            put("processed_path", processedPath)
            put("filter_id", filterId)
            put("rotation", rotation)
            put("corners", corners)
            put("width", width)
            put("height", height)
            put("created_at", now)
        }
        return db().insert("pages", null, values)
    }

    fun getPages(documentId: Long): List<Page> =
        db().rawQuery(
            "$PAGE_SELECT WHERE document_id = ? ORDER BY page_index ASC",
            arrayOf(documentId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toPage())
            }
        }

    fun getPage(pageId: Long): Page? =
        db().rawQuery("$PAGE_SELECT WHERE id = ?", arrayOf(pageId.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.toPage() else null
        }

    fun updatePageResult(
        pageId: Long,
        processedPath: String,
        filterId: Int,
        rotation: Int,
        corners: String?,
        width: Int,
        height: Int,
    ) {
        val values = ContentValues().apply {
            put("processed_path", processedPath)
            put("filter_id", filterId)
            put("rotation", rotation)
            put("corners", corners)
            put("width", width)
            put("height", height)
        }
        db().update("pages", values, "id = ?", arrayOf(pageId.toString()))
    }

    fun setPageOcr(pageId: Long, text: String?, lines: String?) {
        val values = ContentValues().apply {
            put("ocr_text", text)
            put("ocr_lines", lines)
        }
        db().update("pages", values, "id = ?", arrayOf(pageId.toString()))
    }

    fun deletePage(pageId: Long) {
        db().delete("pages", "id = ?", arrayOf(pageId.toString()))
    }

    fun countPages(documentId: Long): Int =
        db().rawQuery("SELECT COUNT(*) AS c FROM pages WHERE document_id = ?", arrayOf(documentId.toString()))
            .use { cursor -> if (cursor.moveToFirst()) cursor.int("c") else 0 }

    /** Rewrites page_index from the given order, atomically. */
    fun reorderPages(documentId: Long, orderedPageIds: List<Long>) {
        val database = db()
        database.beginTransaction()
        try {
            orderedPageIds.forEachIndexed { index, pageId ->
                val values = ContentValues().apply { put("page_index", index) }
                database.update("pages", values, "id = ? AND document_id = ?", arrayOf(pageId.toString(), documentId.toString()))
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun listDocumentIds(): List<Long> =
        db().rawQuery("SELECT id FROM documents", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.number("id")) }
        }

    // ------------------------------------------------------------ mapping

    private fun android.database.Cursor.toDocument() = Document(
        id = number("id"),
        name = text("name"),
        folder = textOrNull("folder"),
        createdAt = number("created_at"),
        updatedAt = number("updated_at"),
        pageCount = int("page_count"),
        coverPath = textOrNull("cover_path"),
        sizeBytes = number("size_bytes"),
        hasOcr = int("ocr_pages") > 0,
    )

    private fun android.database.Cursor.toPage() = Page(
        id = number("id"),
        documentId = number("document_id"),
        index = int("page_index"),
        originalPath = text("original_path"),
        processedPath = text("processed_path"),
        filterId = int("filter_id"),
        rotation = int("rotation"),
        corners = textOrNull("corners"),
        ocrText = textOrNull("ocr_text"),
        ocrLines = textOrNull("ocr_lines"),
        width = int("width"),
        height = int("height"),
        createdAt = number("created_at"),
    )

    private fun DocumentSort.clause(): String = when (this) {
        DocumentSort.DATE_DESC -> "d.updated_at DESC, d.id DESC"
        DocumentSort.DATE_ASC -> "d.updated_at ASC, d.id ASC"
        DocumentSort.NAME_ASC -> "d.name COLLATE NOCASE ASC"
        DocumentSort.NAME_DESC -> "d.name COLLATE NOCASE DESC"
        DocumentSort.SIZE_DESC -> "d.size_bytes DESC"
    }

    private companion object {
        const val DOCUMENT_SELECT = """
            SELECT d.id, d.name, d.folder, d.created_at, d.updated_at, d.cover_path, d.size_bytes,
                   (SELECT COUNT(*) FROM pages p WHERE p.document_id = d.id) AS page_count,
                   (SELECT COUNT(*) FROM pages p WHERE p.document_id = d.id
                      AND p.ocr_text IS NOT NULL AND p.ocr_text <> '') AS ocr_pages
            FROM documents d
        """

        const val PAGE_SELECT = """
            SELECT id, document_id, page_index, original_path, processed_path, filter_id,
                   rotation, corners, ocr_text, ocr_lines, width, height, created_at
            FROM pages
        """
    }
}
