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
                flip_decided INTEGER,
                deskew_degrees REAL NOT NULL DEFAULT 0,
                look_strength REAL NOT NULL DEFAULT 1,
                look_brightness REAL NOT NULL DEFAULT 0,
                look_contrast REAL NOT NULL DEFAULT 0,
                look_sharpness REAL NOT NULL DEFAULT 0,
                render_revision INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_pages_document ON pages(document_id, page_index)")
        db.execSQL("CREATE INDEX idx_documents_folder ON documents(folder)")
        db.execSQL("CREATE INDEX idx_documents_updated ON documents(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive migrations only. Nullable, so there is no backfill: a page written before
        // the column existed reads back as "the pass never ran", which the review does not
        // accuse of being undecided.
        if (oldVersion < 2) db.execSQL("ALTER TABLE pages ADD COLUMN flip_decided INTEGER")

        // The look a page was given, kept beside the pixels so the editor can open the page as it
        // is instead of guessing it. Every column has a non-null default that is also the neutral
        // value — strength 1, adjustments 0, no skew — so a page written before the column existed
        // reads back as "preset only, untouched", which is what it was.
        //
        // render_revision counts rewrites of processed_path. It starts at 0 for existing rows and
        // is bumped by DocumentStore.updatePageResult, which is the only write that replaces the
        // bytes in place; a decode cache keyed on it therefore cannot show a stale page.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE pages ADD COLUMN deskew_degrees REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pages ADD COLUMN look_strength REAL NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE pages ADD COLUMN look_brightness REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pages ADD COLUMN look_contrast REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pages ADD COLUMN look_sharpness REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pages ADD COLUMN render_revision INTEGER NOT NULL DEFAULT 0")
        }
    }

    companion object {
        const val NAME = "kscanner.db"
        const val VERSION = 3
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

internal fun Cursor.real(column: String): Double {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) 0.0 else getDouble(index)
}

/** The column's integer, or null when the stored value is NULL (distinct from 0). */
internal fun Cursor.intOrNull(column: String): Int? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getInt(index)
}
