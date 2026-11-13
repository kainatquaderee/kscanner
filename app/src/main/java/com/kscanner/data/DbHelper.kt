package com.kscanner.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DbHelper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                folder TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                cover_path TEXT,
                size_bytes INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE pages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                document_id INTEGER NOT NULL,
                page_index INTEGER NOT NULL,
                original_path TEXT NOT NULL,
                processed_path TEXT NOT NULL,
                filter_id INTEGER NOT NULL DEFAULT 1,
                rotation INTEGER NOT NULL DEFAULT 0,
                corners TEXT,
                ocr_text TEXT,
                ocr_lines TEXT,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_pages_document ON pages(document_id, page_index)")
        db.execSQL("CREATE INDEX idx_documents_folder ON documents(folder)")
        db.execSQL("CREATE INDEX idx_documents_updated ON documents(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the initial schema. Additive migrations land here.
    }

    companion object {
        const val NAME = "kscanner.db"
        const val VERSION = 1
    }
}

// ---------------------------------------------------------------- cursor helpers

internal fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column)) ?: ""

internal fun Cursor.textOrNull(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

internal fun Cursor.number(column: String): Long {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) 0L else getLong(index)
}

internal fun Cursor.int(column: String): Int = number(column).toInt()
