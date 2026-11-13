package com.kscanner.core.pdf

import com.kscanner.core.ocr.OcrLine
import java.io.File

/** Reads page files off disk and hands them to [PdfWriter]. */
object PdfExporter {

    data class PageSource(
        val jpegFile: File,
        val width: Int,
        val height: Int,
        val ocrLines: List<OcrLine> = emptyList(),
    )

    /**
     * @param includeText when true, OCR lines become a selectable/searchable invisible layer
     * @return the written file size in bytes, or null when nothing could be written
     */
    fun export(
        pages: List<PageSource>,
        destination: File,
        pageSize: PdfWriter.PageSize = PdfWriter.PageSize.A4,
        includeText: Boolean = true,
    ): Long? {
        if (pages.isEmpty()) return null
        val existing = pages.filter { it.jpegFile.exists() && it.jpegFile.length() > 0 }
        if (existing.isEmpty()) return null

        val writer = PdfWriter()
        for (page in existing) {
            val bytes = runCatching { page.jpegFile.readBytes() }.getOrNull() ?: continue
            if (bytes.size < 4) continue
            // Only JPEG streams can be embedded with DCTDecode.
            if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) continue
            val runs = if (includeText) {
                page.ocrLines.map {
                    PdfWriter.TextRun(
                        text = it.text,
                        left = it.left.toDouble(),
                        top = it.top.toDouble(),
                        right = it.right.toDouble(),
                        bottom = it.bottom.toDouble(),
                    )
                }
            } else {
                emptyList()
            }
            writer.addJpegPage(bytes, page.width, page.height, runs)
        }
        if (writer.pageCount == 0) return null

        return try {
            val bytes = writer.build(pageSize)
            destination.parentFile?.mkdirs()
            destination.writeBytes(bytes)
            destination.length()
        } catch (e: Exception) {
            null
        }
    }
}