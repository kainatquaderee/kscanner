package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the detector reports when it is asked for its whole shortlist rather than the winner.
 *
 * The first element is not a matter of taste: the live outline must promise the same outline the
 * crop will take, so it has to be the answer [DocDetector.detect] gives, for every input and both
 * fallback settings. The rest is the material the tracker uses to keep that outline still.
 */
class DetectorRankingTest {

    private val perspective = Quad(Pt(70.0, 60.0), Pt(400.0, 42.0), Pt(420.0, 540.0), Pt(60.0, 520.0))

    private val axisAligned = Quad(Pt(60.0, 50.0), Pt(400.0, 50.0), Pt(400.0, 540.0), Pt(60.0, 540.0))

    /** A page dense with interior rules, the case the edge stage's shortlist exists for. */
    private fun rulesPage(): RgbaImage = TestImages.documentOnBackground(
        canvasWidth = 480,
        canvasHeight = 600,
        pageQuad = Quad(Pt(50.0, 40.0), Pt(430.0, 40.0), Pt(430.0, 560.0), Pt(50.0, 560.0)),
        textLines = 14,
        margin = 14.0,
        barHeight = 5.0,
        lineGap = 30.0,
    )

    /** A photographed sheet, rather than a synthetic one: noise, margins and all. */
    private fun photographed(): RgbaImage {
        val page = RealisticPages.prosePage(900, 1200)
        val pageInFrame = Quad(Pt(40.0, 30.0), Pt(440.0, 20.0), Pt(455.0, 570.0), Pt(30.0, 555.0))
        return RealisticPages.photograph(page, 480, 600, pageInFrame)
    }

    private fun fixtures(): List<RgbaImage> = listOf(
        TestImages.documentOnBackground(480, 600, perspective),
        TestImages.documentOnBackground(480, 600, axisAligned),
        rulesPage(),
        photographed(),
    )

    @Test
    fun `the ranked set starts with the answer detect gives`() {
        for (image in fixtures()) {
            for (fallbacks in listOf(true, false)) {
                for (gray in listOf(false, true)) {
                    val expected = if (gray) {
                        DocDetector.detect(image.toGray(), allowFallbacks = fallbacks)
                    } else {
                        DocDetector.detect(image, allowFallbacks = fallbacks)
                    }
                    val ranked = if (gray) {
                        DocDetector.candidates(image.toGray(), allowFallbacks = fallbacks)
                    } else {
                        DocDetector.candidates(image, allowFallbacks = fallbacks)
                    }
                    assertEquals(
                        "head of the ranking, fallbacks=$fallbacks gray=$gray",
                        expected,
                        ranked.firstOrNull(),
                    )
                }
            }
        }
    }

    @Test
    fun `the rest of the ranking is in descending confidence`() {
        val ranked = DocDetector.candidates(fixtures()[0], allowFallbacks = true)

        assertTrue("the shortlist was empty", ranked.size > 1)
        for (i in 1 until ranked.size - 1) {
            assertTrue(
                "candidate ${i + 1} scored ${ranked[i + 1].confidence} after ${ranked[i].confidence}",
                ranked[i + 1].confidence <= ranked[i].confidence,
            )
        }
    }

    @Test
    fun `the shortlist holds a tighter description of the same page`() {
        // The measured defect's second hypothesis: a quad around the sheet inside the looser one.
        // If only the winner were reported, a caller could never prefer it.
        val image = TestImages.documentOnBackground(480, 600, perspective)
        val ranked = DocDetector.candidates(image, allowFallbacks = false)

        val outer = ranked.first().quad
        // Slack rather than exact containment: competing candidates share sides with the winner, so
        // one of their corners can sit a fraction of a pixel outside its edge and still be the same
        // page described more tightly. 1% of the span is a few pixels on a frame this size.
        val slack = outer.insetBy(-0.01)
        val tighter = ranked.drop(1).filter { inner ->
            inner.quad.area() < outer.area() && inner.quad.points.all { slack.containsPoint(it) }
        }

        assertTrue("no candidate was a tighter quad than the winner: $ranked", tighter.isNotEmpty())
    }

    @Test
    fun `a frame with no page in it has no candidates`() {
        assertTrue(DocDetector.candidates(TestImages.solid(400, 400, 0xFF808080.toInt())).isEmpty())
        assertTrue(DocDetector.candidates(TestImages.solid(400, 400, 0).toGray()).isEmpty())
    }

    @Test
    fun `an image too small to analyse has no candidates`() {
        assertTrue(DocDetector.candidates(TestImages.solid(8, 8, 0)).isEmpty())
        assertTrue(DocDetector.candidates(GrayImage(8, 8, IntArray(64))).isEmpty())
    }

    @Test
    fun `the fallbacks carry the method weights confidence is built from`() {
        // A fallback is worth less than an edge result of the same raw score, and the live tracker
        // gates on confidence: if the weights were dropped here the ink box would outrank a page.
        val image = TestImages.documentOnBackground(480, 600, perspective)
        val ranked = DocDetector.candidates(image, allowFallbacks = true)

        val weighted = ranked.filter { it.method != DetectMethod.EDGES }
        assertTrue("no fallback candidate was reported: $ranked", weighted.isNotEmpty())
        for (candidate in weighted) {
            val weight = if (candidate.method == DetectMethod.BRIGHT_REGION) 0.92 else 0.80
            assertEquals(
                "${candidate.method} confidence was not its score times $weight",
                candidate.score * weight,
                candidate.confidence,
                1e-9,
            )
        }
    }

    @Test
    fun `every candidate is inside the caller's frame and convex`() {
        val image = TestImages.documentOnBackground(480, 600, perspective)

        for (candidate in DocDetector.candidates(image, allowFallbacks = true)) {
            assertTrue("not convex: ${candidate.quad}", candidate.quad.isConvex())
            assertTrue("confidence out of range: $candidate", candidate.confidence in 0.0..1.0)
            for (p in candidate.quad.points) {
                val inFrame = p.x >= -1.0 && p.x <= 481.0 && p.y >= -1.0 && p.y <= 601.0
                assertTrue("corner $p outside the frame", inFrame)
            }
        }
    }
}