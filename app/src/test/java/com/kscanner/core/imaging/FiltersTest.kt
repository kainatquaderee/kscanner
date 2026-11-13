package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FiltersTest {

    private fun page(
        paper: Int = 0xFFF4F4F4.toInt(),
        ink: Int = 0xFF141414.toInt(),
        background: Int = 0xFF303030.toInt(),
    ): RgbaImage = TestImages.documentOnBackground(
        canvasWidth = 300,
        canvasHeight = 400,
        pageQuad = Quad(Pt(30.0, 30.0), Pt(270.0, 30.0), Pt(270.0, 370.0), Pt(30.0, 370.0)),
        paper = paper,
        ink = ink,
        background = background,
    )

    private fun meanLuma(image: RgbaImage): Double {
        var sum = 0L
        for (p in image.pixels) sum += Px.luma(Px.r(p), Px.g(p), Px.b(p))
        return sum.toDouble() / image.size
    }

    private fun lumaStdDev(image: RgbaImage): Double {
        val mean = meanLuma(image)
        var acc = 0.0
        for (p in image.pixels) {
            val d = Px.luma(Px.r(p), Px.g(p), Px.b(p)) - mean
            acc += d * d
        }
        return sqrt(acc / image.size)
    }

    @Test
    fun `original returns an independent copy`() {
        val source = page()
        val result = Filters.apply(source, ScanFilter.ORIGINAL)
        assertNotSame(source, result)
        assertTrue(source.pixels.contentEquals(result.pixels))
    }

    @Test
    fun `grayscale output is perfectly neutral`() {
        val result = Filters.apply(page(), ScanFilter.GRAYSCALE)
        for (p in result.pixels) {
            assertEquals(Px.r(p), Px.g(p))
            assertEquals(Px.g(p), Px.b(p))
        }
    }

    @Test
    fun `black and white output is strictly bi-level`() {
        val result = Filters.apply(page(), ScanFilter.BLACK_WHITE)
        for (p in result.pixels) {
            val v = Px.r(p)
            assertTrue("expected 0 or 255, saw $v", v == 0 || v == 255)
            assertEquals(v, Px.g(p))
            assertEquals(v, Px.b(p))
        }
    }

    @Test
    fun `black and white keeps ink dark and paper light`() {
        val result = Filters.apply(page(), ScanFilter.BLACK_WHITE)
        // Between two text bars, well inside the page: this must stay paper.
        assertEquals(255, Px.r(result.get(150, 155)))
        // And the page interior must not collapse to a single tone.
        var white = 0
        var black = 0
        for (y in 40 until 360) {
            for (x in 40 until 260) {
                if (Px.r(result.get(x, y)) == 255) white++ else black++
            }
        }
        assertTrue("ink should survive, black=$black", black > 200)
        assertTrue("paper should dominate, white=$white", white > black)
    }

    @Test
    fun `magic color brightens a dim capture`() {
        val dim = page(paper = 0xFF969696.toInt(), ink = 0xFF404040.toInt())
        val before = meanLuma(dim)
        val after = meanLuma(Filters.apply(dim, ScanFilter.MAGIC_COLOR))
        assertTrue("expected brightening: before=$before after=$after", after > before + 10)
    }

    @Test
    fun `magic color preserves dimensions`() {
        val source = page()
        val result = Filters.apply(source, ScanFilter.MAGIC_COLOR)
        assertEquals(source.width, result.width)
        assertEquals(source.height, result.height)
    }

    @Test
    fun `lighten lifts shadows`() {
        val dark = page(paper = 0xFF8A8A8A.toInt(), ink = 0xFF303030.toInt())
        val before = meanLuma(dark)
        val after = meanLuma(Filters.apply(dark, ScanFilter.LIGHTEN))
        assertTrue("before=$before after=$after", after > before)
    }

    @Test
    fun `enhance deepens contrast`() {
        val source = page()
        val enhanced = Filters.apply(source, ScanFilter.ENHANCE)
        assertTrue(
            "expected more contrast, before=${lumaStdDev(source)} after=${lumaStdDev(enhanced)}",
            lumaStdDev(enhanced) > lumaStdDev(source),
        )
    }

    @Test
    fun `strength zero leaves the image untouched`() {
        val source = page()
        val result = Filters.apply(source, ScanFilter.BLACK_WHITE, strength = 0f)
        assertTrue(source.pixels.contentEquals(result.pixels))
    }

    @Test
    fun `partial strength lands between the two images`() {
        val source = page()
        val full = Filters.apply(source, ScanFilter.GRAYSCALE, strength = 1f)
        val half = Filters.apply(source, ScanFilter.GRAYSCALE, strength = 0.5f)

        val i = 30 * source.width + 150
        val sourceLuma = Px.luma(Px.r(source.pixels[i]), Px.g(source.pixels[i]), Px.b(source.pixels[i]))
        val fullLuma = Px.r(full.pixels[i])
        val halfLuma = Px.r(half.pixels[i])

        assertTrue(
            "half=$halfLuma should lie between $sourceLuma and $fullLuma",
            halfLuma >= minOf(sourceLuma, fullLuma) - 1 && halfLuma <= maxOf(sourceLuma, fullLuma) + 1,
        )
    }

    @Test
    fun `blend at the endpoints returns the endpoints`() {
        val a = TestImages.solid(8, 8, 0xFF000000.toInt())
        val b = TestImages.solid(8, 8, 0xFFFFFFFF.toInt())
        assertTrue(Filters.blend(a, b, 0f).pixels.contentEquals(a.pixels))
        assertTrue(Filters.blend(a, b, 1f).pixels.contentEquals(b.pixels))
        val middle = Filters.blend(a, b, 0.5f)
        assertEquals(128, Px.r(middle.pixels[0]))
    }

    @Test
    fun `brightness adjustment shifts the mean`() {
        val source = page()
        val brighter = Filters.adjust(source, brightness = 0.25f, contrast = 0f, sharpness = 0f)
        assertTrue(meanLuma(brighter) > meanLuma(source))
    }

    @Test
    fun `contrast adjustment widens the histogram`() {
        val lowContrast = TestImages.solid(64, 64, 0xFF787878.toInt())
        for (y in 0 until 64) {
            for (x in 32 until 64) lowContrast.set(x, y, 0xFF8C8C8C.toInt())
        }
        val boosted = Filters.adjust(lowContrast, brightness = 0f, contrast = 0.6f, sharpness = 0f)
        assertTrue(
            "before=${lumaStdDev(lowContrast)} after=${lumaStdDev(boosted)}",
            lumaStdDev(boosted) > lumaStdDev(lowContrast),
        )
    }

    @Test
    fun `unsharp mask steepens a soft edge`() {
        // A blurred step edge is exactly what sharpening is meant to act on; a linear
        // ramp would be a no-op because blurring it changes nothing.
        val hard = RgbaImage(120, 20, IntArray(120 * 20))
        for (y in 0 until 20) {
            for (x in 0 until 120) {
                val v = if (x < 60) 40 else 200
                hard.set(x, y, Px.rgb(v, v, v))
            }
        }
        val soft = Blur.gaussianBlurRgba(hard, 2.5)
        val sharpened = Filters.unsharpMask(soft, amount = 1.2f)

        val row = 10
        fun slope(image: RgbaImage) = Px.r(image.get(63, row)) - Px.r(image.get(57, row))

        assertTrue(
            "expected a steeper edge, soft=${slope(soft)} sharpened=${slope(sharpened)}",
            slope(sharpened) > slope(soft),
        )
    }
}
