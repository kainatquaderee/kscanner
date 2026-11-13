package com.kscanner.core.pdf

import com.kscanner.core.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Exercises the hand-written PDF writer.
 *
 * The JPEG used here is a real 48x64 baseline stream embedded as base64. AWT and
 * `javax.imageio` are not available to Android unit tests (they are absent from
 * `android.jar`), so a constant payload is the only way to test against genuine
 * DCTDecode bytes rather than a synthetic blob.
 */
class PdfWriterTest {

    /** A real, decodable baseline JPEG. */
    private val sampleJpeg: ByteArray by lazy {
        Base64.getDecoder().decode(SAMPLE_JPEG_BASE64)
    }

    private fun jpeg() = sampleJpeg

    /** Structurally valid JPEG marker bytes; never decoded, only packed. */
    private fun fakeJpeg(payload: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte()) + ByteArray(payload) { 0x7F }

    @Test
    fun `sample payload really is a jpeg`() {
        assertEquals(0xFF.toByte(), sampleJpeg[0])
        assertEquals(0xD8.toByte(), sampleJpeg[1])
        assertTrue(sampleJpeg.size > 500)
    }

    @Test
    fun `writes a well formed single page document`() {
        val bytes = PdfWriter()
            .addJpegPage(jpeg(), 48, 64)
            .build(PdfWriter.PageSize.A4)

        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("missing header", text.startsWith("%PDF-1.4\n"))
        assertTrue("missing catalog", text.contains("/Type /Catalog"))
        assertTrue("missing pages node", text.contains("/Type /Pages"))
        assertTrue("wrong page count", text.contains("/Count 1"))
        assertTrue("image not embedded", text.contains("/Filter /DCTDecode"))
        assertTrue("missing xref", text.contains("\nxref\n"))
        assertTrue("missing trailer", text.contains("/Size "))
        assertTrue("file must end with EOF", text.trimEnd().endsWith("%%EOF"))
    }

    @Test
    fun `embeds one image per page and counts them`() {
        val writer = PdfWriter()
        writer.addJpegPage(jpeg(), 48, 64)
        writer.addJpegPage(fakeJpeg(300), 300, 200)
        writer.addJpegPage(fakeJpeg(100), 100, 100)
        assertEquals(3, writer.pageCount)

        val text = String(writer.build(PdfWriter.PageSize.LETTER), Charsets.ISO_8859_1)
        assertTrue(text.contains("/Count 3"))
        assertEquals(3, Regex("/Subtype /Image").findAll(text).count())
        assertEquals(3, Regex("/Type /Page[^s]").findAll(text).count())
    }

    @Test
    fun `xref offsets point at object headers`() {
        val bytes = PdfWriter().addJpegPage(jpeg(), 48, 64).build()
        val text = String(bytes, Charsets.ISO_8859_1)

        val startxref = text.lastIndexOf("startxref")
        assertTrue(startxref > 0)
        val xrefOffset = text.substring(startxref)
            .lineSequence()
            .drop(1)
            .first()
            .trim()
            .toInt()

        // Every byte offset in the table must land exactly on "N 0 obj".
        val lines = text.substring(xrefOffset).lineSequence().toList()
        assertEquals("xref", lines[0].trim())
        val declaredCount = lines[1].trim().split(" ")[1].toInt()
        val table = lines.drop(2).take(declaredCount)
        assertEquals("xref table is short", declaredCount, table.size)

        // Entry zero is the free-list head and has no object behind it.
        val entries = table.drop(1).map { it.trim().split(" ")[0].toInt() }
        assertTrue("expected a few objects", entries.size >= 4)
        for ((index, offset) in entries.withIndex()) {
            val actual = text.substring(offset, offset + 12)
            assertTrue(
                "object ${index + 1} offset $offset points at '$actual'",
                actual.startsWith("${index + 1} 0 obj"),
            )
        }
    }

    @Test
    fun `declared stream length matches the bytes actually written`() {
        val payload = fakeJpeg(512)
        val bytes = PdfWriter().addJpegPage(payload, 200, 300).build()
        val text = String(bytes, Charsets.ISO_8859_1)
        val declared = Regex("/Filter /DCTDecode /Length (\\d+) >>")
            .find(text)!!
            .groupValues[1]
            .toInt()

        assertEquals("declared length must match the payload exactly", payload.size, declared)
    }

    @Test
    fun `text layer is written invisibly and positioned from bounding boxes`() {
        val run = PdfWriter.TextRun("Hello (scanned) world", 10.0, 20.0, 200.0, 40.0)
        val bytes = PdfWriter().addJpegPage(jpeg(), 48, 64, listOf(run)).build()
        val text = String(bytes, Charsets.ISO_8859_1)

        assertTrue("render mode 3 keeps the text invisible", text.contains("3 Tr"))
        assertTrue("parentheses must be escaped", text.contains("Hello \\(scanned\\) world"))
        assertTrue(text.contains("/BaseFont /Helvetica"))
        assertTrue(text.contains("BT\n3 Tr\n/F1 "))
    }

    @Test
    fun `text placement stays inside the page box`() {
        val run = PdfWriter.TextRun("Invoice 48213", 4.0, 10.0, 44.0, 22.0)
        val text = String(
            PdfWriter().addJpegPage(jpeg(), 48, 64, listOf(run)).build(PdfWriter.PageSize.A4),
            Charsets.ISO_8859_1,
        )
        // A4 is 595.28 x 841.89; an 18pt margin leaves the drawing area inside those bounds.
        val placement = Regex("/F1 [\\d.]+ Tf\\n([\\d.-]+) ([\\d.-]+) Td").find(text)!!
        val x = placement.groupValues[1].toDouble()
        val y = placement.groupValues[2].toDouble()
        assertTrue("x=$x outside the page", x in 0.0..595.0)
        assertTrue("y=$y outside the page", y in 0.0..842.0)
    }

    @Test
    fun `no text layer is emitted when there are no runs`() {
        val text = String(PdfWriter().addJpegPage(jpeg(), 48, 64).build(), Charsets.ISO_8859_1)
        assertFalse("no invisible render mode", text.contains("3 Tr"))
        assertFalse("no text showing operator", text.contains("Tj"))
    }

    @Test
    fun `non latin glyphs are dropped without corrupting escapes`() {
        val run = PdfWriter.TextRun("café — 日本語 ✓", 0.0, 0.0, 100.0, 20.0)
        val text = String(
            PdfWriter().addJpegPage(jpeg(), 48, 64, listOf(run)).build(),
            Charsets.ISO_8859_1,
        )
        assertTrue("latin-1 must survive", text.contains("café"))
        assertTrue("em dash becomes a hyphen", text.contains("café -"))
        assertFalse("CJK cannot be represented in WinAnsi", text.contains("日本語"))
        assertFalse("dingbats are dropped too", text.contains("✓"))
    }

    @Test
    fun `fit-to-image keeps the source aspect ratio`() {
        val text = String(
            PdfWriter().addJpegPage(fakeJpeg(64), 400, 1000).build(PdfWriter.PageSize.FIT_IMAGE),
            Charsets.ISO_8859_1,
        )
        val mediaBox = Regex("/MediaBox \\[ 0 0 ([\\d.]+) ([\\d.]+) \\]").find(text)!!
        val width = mediaBox.groupValues[1].toDouble()
        val height = mediaBox.groupValues[2].toDouble()
        assertEquals(400.0 / 1000.0, width / height, 0.01)
    }

    @Test
    fun `fit-to-image clamps very large scans`() {
        val text = String(
            PdfWriter().addJpegPage(fakeJpeg(64), 9000, 12000).build(PdfWriter.PageSize.FIT_IMAGE),
            Charsets.ISO_8859_1,
        )
        val mediaBox = Regex("/MediaBox \\[ 0 0 ([\\d.]+) ([\\d.]+) \\]").find(text)!!
        val longest = maxOf(mediaBox.groupValues[1].toDouble(), mediaBox.groupValues[2].toDouble())
        assertTrue("longest edge was $longest", longest <= 1440.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an empty jpeg`() {
        PdfWriter().addJpegPage(ByteArray(0), 10, 10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects zero image dimensions`() {
        PdfWriter().addJpegPage(jpeg(), 0, 64)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects building with no pages`() {
        PdfWriter().build()
    }

    @Test
    fun `exporter skips unreadable pages but keeps the rest`() {
        val dir = File("build/verification/tmp")
        dir.mkdirs()
        val good = File(dir, "page-ok.jpg").apply { writeBytes(sampleJpeg) }
        val missing = File(dir, "page-missing.jpg")
        missing.delete()
        val notJpeg = File(dir, "page-not.jpg").apply { writeBytes(ByteArray(64) { 1 }) }
        val destination = File(dir, "export.pdf")

        val size = PdfExporter.export(
            pages = listOf(
                PdfExporter.PageSource(good, 48, 64, listOf(OcrLine("kept", 2, 4, 30, 16))),
                PdfExporter.PageSource(missing, 48, 64),
                PdfExporter.PageSource(notJpeg, 48, 64),
            ),
            destination = destination,
            includeText = true,
        )

        assertTrue("export should still succeed", size != null && size!! > 1000L)
        val text = destination.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("/Count 1"))
        assertTrue(text.contains("(kept) Tj"))
    }

    @Test
    fun `exporter returns null when nothing can be written`() {
        val destination = File("build/verification/nothing.pdf")
        destination.delete()
        val result = PdfExporter.export(
            pages = listOf(PdfExporter.PageSource(File("build/verification/absent.jpg"), 10, 10)),
            destination = destination,
        )
        assertEquals(null, result)
    }

    @Test
    fun `exporter can omit the text layer`() {
        val dir = File("build/verification/plain")
        dir.mkdirs()
        val page = File(dir, "page.jpg").apply { writeBytes(sampleJpeg) }
        val textMode = File(dir, "with-text.pdf")
        val imageMode = File(dir, "without-text.pdf")

        val lines = listOf(OcrLine("hidden", 2, 4, 30, 16))
        PdfExporter.export(
            listOf(PdfExporter.PageSource(page, 48, 64, lines)),
            textMode,
            includeText = true,
        )
        PdfExporter.export(
            listOf(PdfExporter.PageSource(page, 48, 64, lines)),
            imageMode,
            includeText = false,
        )

        assertTrue(textMode.readBytes().toString(Charsets.ISO_8859_1).contains("(hidden) Tj"))
        assertFalse(imageMode.readBytes().toString(Charsets.ISO_8859_1).contains("(hidden) Tj"))
    }

    /**
     * Writes a real two-page searchable PDF so external tools (`pdfinfo`, `pdftotext`)
     * can validate the exact bytes the app emits.
     */
    @Test
    fun `emits a verification file for external tooling`() {
        val destination = File("build/verification/ocr-sample.pdf")
        destination.parentFile?.mkdirs()

        val pageOne = PdfWriter.TextRun("Invoice number 48213", 3.0, 6.0, 44.0, 14.0)
        val pageTwo = PdfWriter.TextRun("Total due 1204.50 EUR", 3.0, 6.0, 45.0, 14.0)

        val bytes = PdfWriter()
            .addJpegPage(sampleJpeg, 48, 64, listOf(pageOne))
            .addJpegPage(sampleJpeg, 48, 64, listOf(pageTwo))
            .build(PdfWriter.PageSize.A4)

        destination.writeBytes(bytes)
        assertTrue("verification pdf is unexpectedly small", destination.length() > 2000L)
        println("VERIFICATION_PDF=${destination.absolutePath}")
    }

    private companion object {
        const val SAMPLE_JPEG_BASE64 =
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA0JCgsKCA0LCgsODg0PEyAVExISEyccHhcgLikxMC4pLSwzOko+MzZGNywtQFdB" +
                "RkxOUlNSMj5aYVpQYEpRUk//2wBDAQ4ODhMREyYVFSZPNS01T09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09P" +
                "T09PT09PT09PT09PT0//wAARCABAADADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAA" +
                "AgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6" +
                "Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
                "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREA" +
                "AgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5" +
                "OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPE" +
                "xcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD0O7k1FJQLK1tZo9vLS3LRnPpgRtx05zUH" +
                "na5/0DtO/wDA9/8A4zU93Zz3EoeLU7q1ULjZEsRB9/nRjn8e1Qf2bef9B7Uf++Lf/wCNUAHna5/0DtO/8D3/APjNHna5/wBA" +
                "7Tv/AAPf/wCM0f2bef8AQe1H/vi3/wDjVH9m3n/Qe1H/AL4t/wD41QAedrn/AEDtO/8AA9//AIzU9pJqLykXtrawx7eGiuWk" +
                "OfTBjXjrzmoP7NvP+g9qP/fFv/8AGqntLOe3lLy6ndXSlcbJViAHv8iKc/j3oAqasmjNdKdS0z7VLsGH/s57jC5PG4Icc54z" +
                "396o+V4Y/wCgB/5RJf8A43WzdyaikoFla2s0e3lpblozn0wI246c5qDztc/6B2nf+B7/APxmgDN8rwx/0AP/ACiS/wDxujyv" +
                "DH/QA/8AKJL/APG60vO1z/oHad/4Hv8A/GaPO1z/AKB2nf8Age//AMZoAzfK8Mf9AD/yiS//ABur2kpoy3THTdM+yy7Dl/7O" +
                "e3yuRxuKDPOOM9vapPO1z/oHad/4Hv8A/GantJNReUi9tbWGPbw0Vy0hz6YMa8dec0AF3Zz3EoeLU7q1ULjZEsRB9/nRjn8e" +
                "1Qf2bef9B7Uf++Lf/wCNVHqyaM10p1LTPtUuwYf+znuMLk8bghxznjPf3qj5Xhj/AKAH/lEl/wDjdAGl/Zt5/wBB7Uf++Lf/" +
                "AONUf2bef9B7Uf8Avi3/APjVZvleGP8AoAf+USX/AON0eV4Y/wCgB/5RJf8A43QBpf2bef8AQe1H/vi3/wDjVT2lnPbyl5dT" +
                "urpSuNkqxAD3+RFOfx71jeV4Y/6AH/lEl/8AjdXtJTRlumOm6Z9ll2HL/wBgPb5XI43FBnnHGe3tQBbu5NRSUCytbWaPby0t" +
                "y0Zz6YEbcdOc1B52uf8AQO07/wAD3/8AjNT3dnPcSh4tTurVQuNkSxEH3+dGOfx7VB/Zt5/0HtR/74t//jVAB52uf9A7Tv8A" +
                "wPf/AOM0edrn/QO07/wPf/4zR/Zt5/0HtR/74t//AI1R/Zt5/wBB7Uf++Lf/AONUAHna5/0DtO/8D3/+M1PaSai8pF7a2sMe" +
                "3horlpDn0wY14685qD+zbz/oPaj/AN8W/wD8aqe0s57eUvLqd1dKVxslWIAe/wAiKc/j3oA//9k="
    }
}
