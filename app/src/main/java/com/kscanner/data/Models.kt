package com.kscanner.data

/** A scanned document: an ordered collection of pages. */
data class Document(
    val id: Long,
    val name: String,
    val folder: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    val coverPath: String?,
    val sizeBytes: Long,
    val hasOcr: Boolean,
)

/** One scanned page. The original capture is retained so filters can be re-applied later. */
data class Page(
    val id: Long,
    val documentId: Long,
    val index: Int,
    val originalPath: String,
    val processedPath: String,
    val filterId: Int,
    val rotation: Int,
    /** Serialized outline, or null when the full frame was used. */
    val corners: String?,
    val ocrText: String?,
    /** Encoded OCR line geometry; used to build the searchable PDF text layer. */
    val ocrLines: String?,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    /**
     * Whether the automatic pass could decide the 0-versus-180 flip, or null when no plan
     * ran for this page (an import, a page written before the flag existed).
     *
     * Null is *not* the same as false: a page that predates the flag must never be shown as
     * one the app could not orient. Only `false` earns the review's "could not tell" prompt.
     */
    val flipDecided: Boolean? = null,
) {
    /**
     * Moves whenever the bytes at [processedPath] are rewritten in place — a rotation or a
     * filter change. A decode cache keys on it beside the file's own length and mtime, which
     * can miss a same-length rewrite inside one timestamp tick.
     */
    val renderRevision: Int get() = rotation * 1000 + filterId
}

enum class DocumentSort(val label: String) {
    DATE_DESC("Newest"),
    DATE_ASC("Oldest"),
    NAME_ASC("Name A-Z"),
    NAME_DESC("Name Z-A"),
    SIZE_DESC("Largest"),
}
