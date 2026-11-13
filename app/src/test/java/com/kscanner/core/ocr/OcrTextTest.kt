package com.kscanner.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextTest {

    private fun line(text: String, top: Int = 0) = OcrLine(text, 10, top, 200, top + 20)

    @Test
    fun `plain text joins bracket adjacent lines with a single newline`() {
        val lines = listOf(line("First line", 0), line("Second line", 24), line("Third line", 48))
        assertEquals("First line\nSecond line\nThird line", OcrText.plainText(lines))
    }

    @Test
    fun `plain text inserts a blank line across a paragraph gap`() {
        val lines = listOf(line("Heading", 0), line("Body text", 100))
        assertEquals("Heading\n\nBody text", OcrText.plainText(lines))
    }

    @Test
    fun `empty input yields an empty string`() {
        assertEquals("", OcrText.plainText(emptyList()))
    }

    @Test
    fun `round trips geometry and text`() {
        val original = listOf(
            OcrLine("Invoice 42", 12, 30, 210, 52),
            OcrLine("Total: 9.99", 12, 80, 160, 100),
        )
        val decoded = OcrText.decode(OcrText.encode(original))
        assertEquals(2, decoded.size)
        assertEquals(original, decoded)
    }

    @Test
    fun `round trips text containing pipes backslashes and newlines`() {
        val tricky = listOf(
            OcrLine("a|b", 0, 0, 10, 10),
            OcrLine("C:\\path\\to", 0, 20, 10, 30),
            OcrLine("two\nlines", 0, 40, 10, 50),
            OcrLine("escape\\|pipe", 0, 60, 10, 70),
            OcrLine("trailing backslash \\", 0, 80, 10, 90),
        )
        val decoded = OcrText.decode(OcrText.encode(tricky))

        assertEquals(5, decoded.size)
        assertEquals("a|b", decoded[0].text)
        assertEquals("C:\\path\\to", decoded[1].text)
        assertTrue(decoded[2].text.contains('\n'))
        assertEquals("escape\\|pipe", decoded[3].text)
        assertEquals("trailing backslash \\", decoded[4].text)
    }

    @Test
    fun `decode is defensive about malformed records`() {
        assertTrue(OcrText.decode(null).isEmpty())
        assertTrue(OcrText.decode("").isEmpty())
        assertTrue(OcrText.decode("not|a|record").isEmpty())
        assertTrue(OcrText.decode("a|b|c|d|text").isEmpty())
        // A record with a valid prefix but no text is dropped.
        assertTrue(OcrText.decode("1|2|3|4|").isEmpty())
    }

    @Test
    fun `line height is never zero`() {
        assertEquals(1, OcrLine("x", 10, 10, 20, 10).height)
    }

    @Test
    fun `encoded records never contain a raw newline inside a field`() {
        val encoded = OcrText.encode(listOf(OcrLine("a\nb", 0, 0, 5, 5)))
        assertEquals("one record", 1, encoded.split("\n").size)
    }
}
