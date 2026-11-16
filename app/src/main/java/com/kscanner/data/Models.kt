package com.kscanner.data

import com.kscanner.core.imaging.PageLook
import com.kscanner.core.imaging.ScanFilter

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
    /** Residual text skew removed from the cropped page, in degrees (clockwise positive). */
    val deskewDegrees: Double = 0.0,
    /** Preset strength the page was rendered at, 0..1. */
    val strength: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val sharpness: Float = 0f,
    /**
     * Counts rewrites of the bytes at [processedPath]. `DocumentStore.updatePageResult` bumps it
     * in the same statement that points the row at a new render, so it is impossible to rewrite a
     * page without the number moving.
     */
    val renderRevision: Long = 0L,
    /**
     * Whether the automatic pass could decide the 0-versus-180 flip, or null when no plan
     * ran for this page (an import, a page written before the flag existed).
     *
     * Null is *not* the same as false: a page that predates the flag must never be shown as
     * one the app could not orient. Only `false` earns the review's "could not tell" prompt.
     */
    val flipDecided: Boolean? = null,
) {
    /** Preset and adjustments together; see [PageLook] for why the two travel as one value. */
    val look: PageLook
        get() = PageLook(
            filter = ScanFilter.fromId(filterId),
            strength = strength,
            brightness = brightness,
            contrast = contrast,
            sharpness = sharpness,
        )
}

enum class DocumentSort(val label: String) {
    DATE_DESC("Newest"),
    DATE_ASC("Oldest"),
    NAME_ASC("Name A-Z"),
    NAME_DESC("Name Z-A"),
    SIZE_DESC("Largest"),
}
