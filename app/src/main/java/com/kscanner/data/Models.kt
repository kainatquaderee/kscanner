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
)

enum class DocumentSort(val label: String) {
    DATE_DESC("Newest"),
    DATE_ASC("Oldest"),
    NAME_ASC("Name A-Z"),
    NAME_DESC("Name Z-A"),
    SIZE_DESC("Largest"),
}
