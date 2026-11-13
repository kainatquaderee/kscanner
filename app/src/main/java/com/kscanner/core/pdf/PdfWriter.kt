package com.kscanner.core.pdf

import java.io.ByteArrayOutputStream

/**
 * A minimal, dependency-free PDF 1.4 writer tailored to scanned documents.
 *
 * Written by hand rather than via `android.graphics.pdf.PdfDocument` for one decisive
 * reason: searchable scans need a real invisible text layer (`3 Tr` render mode), which
 * the platform API cannot express. Every page is a single DCTDecode (JPEG) image plus an
 * optional text layer positioned from OCR bounding boxes.
 *
 * The writer is plain JVM code with no Android imports, so the exact bytes shipped to
 * users are the bytes exercised by the unit tests.
 */
class PdfWriter {

    /** One OCR text run, in image pixel coordinates. */
    data class TextRun(
        val text: String,
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    )

    /** Physical page geometry in PDF points (1/72 inch). */
    enum class PageSize(val widthPt: Double, val heightPt: Double) {
        A4(595.28, 841.89),
        LETTER(612.0, 792.0),

        /** Page takes the image's own aspect ratio at roughly 150 dpi. */
        FIT_IMAGE(0.0, 0.0),
    }

    private data class Page(
        val jpeg: ByteArray,
        val imageWidth: Int,
        val imageHeight: Int,
        val textRuns: List<TextRun>,
    )

    private val pages = ArrayList<Page>()

    fun addJpegPage(jpeg: ByteArray, imageWidth: Int, imageHeight: Int, textRuns: List<TextRun> = emptyList()): PdfWriter {
        require(jpeg.isNotEmpty()) { "empty JPEG payload" }
        require(imageWidth > 0 && imageHeight > 0) { "invalid image dimensions" }
        pages.add(Page(jpeg, imageWidth, imageHeight, textRuns))
        return this
    }

    val pageCount: Int get() = pages.size

    fun build(pageSize: PageSize = PageSize.A4): ByteArray {
        require(pages.isNotEmpty()) { "a PDF needs at least one page" }

        val out = ByteArrayOutputStream(1 shl 20)

        // ---- object allocation. Ids are reserved up front so dictionaries can cross-reference.
        val bodies = ArrayList<ByteArray?>()
        fun reserve(): Int {
            bodies.add(null)
            return bodies.size // 1-based object number
        }
        fun set(id: Int, body: String) {
            bodies[id - 1] = body.toByteArray(Charsets.ISO_8859_1)
        }

        val catalogId = reserve()
        val pagesId = reserve()
        val fontId = reserve()

        data class Layout(val pageId: Int, val contentId: Int, val imageId: Int, val widthPt: Double, val heightPt: Double, val content: String)

        val layouts = ArrayList<Layout>()

        for (page in pages) {
            val (pageWidth, pageHeight) = resolvePageSize(pageSize, page.imageWidth, page.imageHeight)
            val margin = if (pageSize == PageSize.FIT_IMAGE) 0.0 else 18.0
            val availWidth = pageWidth - margin * 2
            val availHeight = pageHeight - margin * 2
            val scale = minOf(availWidth / page.imageWidth, availHeight / page.imageHeight)
            val drawWidth = page.imageWidth * scale
            val drawHeight = page.imageHeight * scale
            val offsetX = (pageWidth - drawWidth) / 2.0
            val offsetY = (pageHeight - drawHeight) / 2.0

            val pageId = reserve()
            val contentId = reserve()
            val imageId = reserve()

            val content = StringBuilder(256)
            content.append("q\n")
            content.append(fmt(drawWidth)).append(" 0 0 ").append(fmt(drawHeight)).append(' ')
            content.append(fmt(offsetX)).append(' ').append(fmt(offsetY)).append(" cm\n")
            content.append("/Im0 Do\n")
            content.append("Q\n")

            if (page.textRuns.isNotEmpty()) {
                content.append("q\n")
                for (run in page.textRuns) {
                    val cleaned = sanitizeText(run.text)
                    if (cleaned.isEmpty()) continue

                    // Image pixels -> points, then -> PDF user space (origin bottom-left).
                    val runHeight = (run.bottom - run.top) * scale
                    if (runHeight <= 0.0) continue
                    val fontSize = (runHeight * 0.82).coerceIn(2.0, 72.0)

                    val x = offsetX + run.left * scale
                    val y = pageHeight - (offsetY + run.bottom * scale)

                    // `3 Tr` is the invisible render mode: the text is in the file for
                    // selection and search, but never painted over the scan.
                    content.append("BT\n3 Tr\n/F1 ").append(fmt(fontSize)).append(" Tf\n")
                    content.append(fmt(x)).append(' ').append(fmt(y)).append(" Td\n")
                    content.append('(').append(escapeText(cleaned)).append(") Tj\nET\n")
                }
                content.append("Q\n")
            }

            layouts.add(Layout(pageId, contentId, imageId, pageWidth, pageHeight, content.toString()))
        }

        // ---- fixed objects
        set(
            catalogId,
            "<< /Type /Catalog /Pages $pagesId 0 R >>",
        )
        val kids = layouts.joinToString(" ") { "${it.pageId} 0 R" }
        set(
            pagesId,
            "<< /Type /Pages /Kids [ $kids ] /Count ${layouts.size} >>",
        )
        set(
            fontId,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        )

        for (layout in layouts) {
            set(
                layout.pageId,
                "<< /Type /Page /Parent $pagesId 0 R " +
                    "/MediaBox [ 0 0 ${fmt(layout.widthPt)} ${fmt(layout.heightPt)} ] " +
                    "/Resources << /XObject << /Im0 ${layout.imageId} 0 R >> " +
                    "/Font << /F1 $fontId 0 R >> >> " +
                    "/Contents ${layout.contentId} 0 R >>",
            )
            val contentBytes = layout.content.toByteArray(Charsets.ISO_8859_1)
            set(
                layout.contentId,
                "<< /Length ${contentBytes.size} >>\nstream\n" + layout.content + "endstream",
            )
        }

        // ---- header
        out.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        // A binary comment marks the file as containing binary data, which keeps naive
        // transfer tools from treating it as text.
        out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        // ---- body, recording byte offsets for the xref table
        val offsets = IntArray(bodies.size + 1)
        var pageIndex = 0
        for (i in bodies.indices) {
            val id = i + 1
            offsets[id] = out.size()
            out.write("$id 0 obj\n".toByteArray(Charsets.ISO_8859_1))

            val body = bodies[i]
            when {
                body != null -> out.write(body)
                // Image objects are the only place raw binary is emitted.
                pageIndex < layouts.size && layouts[pageIndex].imageId == id -> {
                    val jpeg = pages[pageIndex].jpeg
                    val dict = "<< /Type /XObject /Subtype /Image /Width ${pages[pageIndex].imageWidth} " +
                        "/Height ${pages[pageIndex].imageHeight} /ColorSpace /DeviceRGB " +
                        "/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n"
                    out.write(dict.toByteArray(Charsets.ISO_8859_1))
                    out.write(jpeg)
                    out.write("\nendstream".toByteArray(Charsets.ISO_8859_1))
                    pageIndex++
                }
                else -> error("unset PDF object $id")
            }
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }

        // ---- cross-reference table
        val xrefOffset = out.size()
        out.write("xref\n0 ${bodies.size + 1}\n".toByteArray(Charsets.ISO_8859_1))
        out.write("0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
        for (id in 1..bodies.size) {
            out.write(String.format("%010d 00000 n \n", offsets[id]).toByteArray(Charsets.ISO_8859_1))
        }

        out.write(
            (
                "trailer\n<< /Size ${bodies.size + 1} /Root $catalogId 0 R >>\n" +
                    "startxref\n$xrefOffset\n%%EOF\n"
                ).toByteArray(Charsets.ISO_8859_1),
        )

        return out.toByteArray()
    }

    private fun resolvePageSize(size: PageSize, imageWidth: Int, imageHeight: Int): Pair<Double, Double> {
        if (size != PageSize.FIT_IMAGE) return Pair(size.widthPt, size.heightPt)

        // 150 dpi: half the pixel count expressed in points.
        val w = imageWidth / 2.0
        val h = imageHeight / 2.0
        val maxEdge = 1440.0
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return Pair(w, h)
        val s = maxEdge / longest
        return Pair(w * s, h * s)
    }

    private fun fmt(value: Double): String {
        val rounded = Math.round(value * 1000.0) / 1000.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun escapeText(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            when (c) {
                '(' -> sb.append("\\(")
                ')' -> sb.append("\\)")
                '\\' -> sb.append("\\\\")
                '\n', '\r', '\t' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * WinAnsi only covers Western European glyphs. Anything outside Latin-1 is dropped
     * rather than written as mojibake, so the visible image stays authoritative and the
     * text layer never corrupts a search index.
     */
    private fun sanitizeText(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c == ' ' || c == '\t' -> sb.append(' ')
                c.code in 0x20..0x7E -> sb.append(c)
                c.code in 0xA0..0xFF -> sb.append(c)
                c == '\u2018' || c == '\u2019' -> sb.append('\'')
                c == '\u201C' || c == '\u201D' -> sb.append('"')
                c == '\u2013' || c == '\u2014' -> sb.append('-')
                c == '\u2026' -> sb.append("...")
                c == '\u00A0' -> sb.append(' ')
                else -> Unit
            }
        }
        return sb.toString().trim()
    }
}