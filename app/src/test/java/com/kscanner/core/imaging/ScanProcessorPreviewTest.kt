package com.kscanner.core.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The seam the editor re-renders through.
 *
 * The editor draws a preview once per gesture but re-applies the filter on every slider frame, so
 * [ScanProcessor.preview] was split into [ScanProcessor.previewGeometry] (rotation, cropping,
 * deskew — the expensive half) and [ScanProcessor.enhance] (the look). The split is only safe while
 * the two halves still compose to exactly the image the single pass used to produce, and while the
 * geometry half really is independent of the look. Both are pinned here as pixel equality, because
 * "almost the same picture" is not a property anything downstream can check.
 *
 * Nothing here needs a device or a clock: every fixture is a synthetic buffer and every comparison
 * is over integer pixels, so the file is deterministic and safe to run in any order.
 */
class ScanProcessorPreviewTest {

    private companion object {
        /**
         * A page outline inside the 360x480 frame below. Not edge to edge, so the cropped result is
         * genuinely smaller than the frame and cropping cannot be mistaken for a no-op.
         */
        val CAPTURE_QUAD = Quad(
            Pt(50.0, 40.0),
            Pt(320.0, 30.0),
            Pt(335.0, 440.0),
            Pt(40.0, 430.0),
        )

        /** The same outline expressed in a 480x360 frame, i.e. the source after a 90 turn. */
        val ROTATED_QUAD = Quad(
            Pt(60.0, 40.0),
            Pt(420.0, 40.0),
            Pt(420.0, 320.0),
            Pt(60.0, 320.0),
        )
    }

    /**
     * A capture with something for every stage to chew on: a light page on a dark surface carrying
     * text rows, so cropping changes the size, the rotation changes the layout and each filter
     * changes the pixels.
     */
    private fun capture(): RgbaImage = TestImages.documentOnBackground(
        canvasWidth = 360,
        canvasHeight = 480,
        pageQuad = CAPTURE_QUAD,
    )

    private fun assertSamePixels(what: String, expected: RgbaImage, actual: RgbaImage) {
        assertEquals("$what: width", expected.width, actual.width)
        assertEquals("$what: height", expected.height, actual.height)
        assertArrayEquals("$what: pixels", expected.pixels, actual.pixels)
    }

    // ------------------------------------------------------------------ the split is faithful

    /**
     * [ScanProcessor.preview] must equal [ScanProcessor.enhance] of
     * [ScanProcessor.previewGeometry], pixel for pixel, for every combination of geometry and look.
     *
     * Stated plainly: by construction this holds today, because `preview` is written as exactly
     * that composition. The value of the assertion is as a guard on the *composition* rather than on
     * the arithmetic — if anyone moves a stage from `enhance` back into `preview`, reorders them, or
     * lets the two drift so the editor previews one picture and the saved page is another, this
     * fails. It is a contract test, not an independent derivation.
     *
     * Both budgets are exercised: 900 is larger than the fixture, so nothing is downscaled before
     * cropping (the scale-1 path); 240 is smaller, which is the case that scales the outline into
     * the reduced frame — the arithmetic the split introduced.
     */
    @Test
    fun `preview is enhance of the geometry, pixel for pixel`() {
        val source = capture()
        val settings = listOf(
            // Rotation alone, with the default filter.
            ScanProcessor.Settings(rotationDegrees = 90),
            ScanProcessor.Settings(rotationDegrees = 180, quad = CAPTURE_QUAD),
            ScanProcessor.Settings(rotationDegrees = 270, quad = ROTATED_QUAD, deskewDegrees = 1.4),
            // Cropping, at a partial filter strength.
            ScanProcessor.Settings(
                quad = CAPTURE_QUAD,
                filter = ScanFilter.BLACK_WHITE,
                strength = 0.6f,
            ),
            // A flat-field filter plus every manual slider at once.
            ScanProcessor.Settings(
                quad = CAPTURE_QUAD,
                filter = ScanFilter.SHADOW_FREE,
                brightness = 0.2f,
                contrast = -0.3f,
                sharpness = 0.5f,
            ),
            // Nothing applied: ORIGINAL at zero strength, a deskew the only operation left.
            ScanProcessor.Settings(
                quad = CAPTURE_QUAD,
                deskewDegrees = -2.0,
                filter = ScanFilter.ORIGINAL,
                strength = 0f,
            ),
        )

        for (edge in intArrayOf(240, 900)) {
            for (s in settings) {
                val viaGeometry = ScanProcessor.enhance(ScanProcessor.previewGeometry(source, s, edge), s)
                val direct = ScanProcessor.preview(source, s, edge)
                assertSamePixels("edge $edge rotation ${s.rotationDegrees} filter ${s.filter}", viaGeometry, direct)
            }
        }
    }

    /**
     * The geometry half must not depend on the look at all — that is the whole reason the editor
     * can cache it. The filter, the strength and all three manual sliders change and the geometry's
     * pixels and its `edge` must come back identical.
     */
    @Test
    fun `previewGeometry ignores the filter and the manual sliders`() {
        val source = capture()
        val geometry = ScanProcessor.Settings(
            rotationDegrees = 90,
            quad = ROTATED_QUAD,
            deskewDegrees = 0.7,
        )

        val baseline = ScanProcessor.previewGeometry(source, geometry, previewEdge = 240)

        for (filter in ScanFilter.entries) {
            val withLook = geometry.copy(
                filter = filter,
                strength = 0.35f,
                brightness = 0.4f,
                contrast = -0.25f,
                sharpness = 0.6f,
            )
            val result = ScanProcessor.previewGeometry(source, withLook, previewEdge = 240)
            assertSamePixels("look $filter", baseline.image, result.image)
            assertEquals("look $filter: edge", baseline.edge, result.edge)
        }

        // And with the sliders set differently again, one more pass with the default look.
        val extremes = geometry.copy(brightness = -1f, contrast = 1f, sharpness = 1f, strength = 0f)
        assertSamePixels(
            "extreme sliders",
            baseline.image,
            ScanProcessor.previewGeometry(source, extremes, previewEdge = 240).image,
        )

        assertEquals("the geometry budget is the requested one", 240, baseline.edge)
    }

    /**
     * The same look with a different body of geometry must not be reachable through
     * [ScanProcessor.previewGeometry]: the geometry it returns is the crop, and the crop is
     * genuinely smaller than its source. Without this the pixel-equality tests above could all be
     * passing on a path that never crops.
     */
    @Test
    fun `the geometry half really crops`() {
        val source = capture()
        val withQuad = ScanProcessor.previewGeometry(source, ScanProcessor.Settings(quad = CAPTURE_QUAD), 900)
        val withoutQuad = ScanProcessor.previewGeometry(source, ScanProcessor.Settings(), 900)

        assertEquals("no crop keeps the frame width", source.width, withoutQuad.image.width)
        assertEquals("no crop keeps the frame height", source.height, withoutQuad.image.height)
        assertNotEquals("the outline was not applied", source.width, withQuad.image.width)
        assertNotEquals("the outline was not applied", source.height, withQuad.image.height)
    }

    // ------------------------------------------------------------------ cropIfNeeded guards

    /**
     * An outline entirely off the frame collapses to nothing when it is clamped, and
     * [ScanProcessor] then keeps the whole frame rather than warping a degenerate region. The
     * result must be byte-identical to the same call with no outline at all.
     */
    @Test
    fun `an outline off the frame is ignored`() {
        val source = capture()
        val plain = ScanProcessor.preview(source, ScanProcessor.Settings(filter = ScanFilter.ORIGINAL), 900)

        val outside = listOf(
            // Clears the frame on every side: every corner clamps to (0, 0).
            Quad(Pt(-400.0, -400.0), Pt(-200.0, -400.0), Pt(-200.0, -200.0), Pt(-400.0, -200.0)),
            // Straddles the top-left corner, leaving a 3x3 sliver (area 9 < 64).
            Quad(Pt(-20.0, -20.0), Pt(3.0, -20.0), Pt(3.0, 3.0), Pt(-20.0, 3.0)),
            // A zero-area outline: all four corners on the same spot.
            Quad(Pt(120.0, 120.0), Pt(120.0, 120.0), Pt(120.0, 120.0), Pt(120.0, 120.0)),
        )

        for (quad in outside) {
            val image = ScanProcessor.preview(
                source,
                ScanProcessor.Settings(quad = quad, filter = ScanFilter.ORIGINAL),
                900,
            )
            assertSamePixels("outline $quad is off frame", plain, image)
        }
    }

    /**
     * A frame-sized outline is skipped on purpose: warping the whole frame through the homography
     * would only resample it and lose detail for no crop at all. The threshold is
     * `clamped area / frame > 0.995`.
     *
     * The fixture is exactly 1000x1000 with `previewEdge = 1000`, so `previewGeometry` scales by 1
     * and the outline reaches `cropIfNeeded` unscaled. The frame outline's own dimensions are what
     * makes this observable: were it warped instead of skipped, the result would be the outline's
     * unwarped size (999x999, since the corners clamp to width-1), not the frame.
     */
    @Test
    fun `a frame sized outline is skipped rather than resampled`() {
        val source = TestImages.textPage(width = 1000, height = 1000, lines = 30)
        val frame = Quad(Pt(0.0, 0.0), Pt(1000.0, 0.0), Pt(1000.0, 1000.0), Pt(0.0, 1000.0))

        val skipped = ScanProcessor.preview(
            source,
            ScanProcessor.Settings(quad = frame, filter = ScanFilter.ORIGINAL),
            previewEdge = 1000,
        )
        val plain = ScanProcessor.preview(
            source,
            ScanProcessor.Settings(filter = ScanFilter.ORIGINAL),
            previewEdge = 1000,
        )

        assertEquals("the frame keeps its width", 1000, skipped.width)
        assertEquals("the frame keeps its height", 1000, skipped.height)
        assertSamePixels("a frame-sized outline is a no-op", plain, skipped)
    }

    /** A one-pixel source goes through every stage without being resized or rejected. */
    @Test
    fun `a one pixel source survives the pipeline`() {
        val source = RgbaImage.filled(1, 1, 0xFF808080.toInt())

        val settings = listOf(
            ScanProcessor.Settings(filter = ScanFilter.ORIGINAL),
            ScanProcessor.Settings(rotationDegrees = 90, filter = ScanFilter.ORIGINAL),
            ScanProcessor.Settings(filter = ScanFilter.MAGIC_COLOR),
            ScanProcessor.Settings(filter = ScanFilter.BLACK_WHITE),
            ScanProcessor.Settings(
                quad = Quad(Pt(0.0, 0.0), Pt(0.0, 0.0), Pt(0.0, 0.0), Pt(0.0, 0.0)),
                filter = ScanFilter.SHADOW_FREE,
                deskewDegrees = 3.0,
                brightness = 0.5f,
                contrast = 0.5f,
                sharpness = 1f,
            ),
        )

        for (s in settings) {
            val image = ScanProcessor.preview(source, s)
            assertEquals("filter ${s.filter} rotation ${s.rotationDegrees}: width", 1, image.width)
            assertEquals("filter ${s.filter} rotation ${s.rotationDegrees}: height", 1, image.height)
        }
    }

    /**
     * A deskew below an eighth of a degree is not applied: [Transforms.rotateArbitrary] resolves
     * nothing finer, so rotating would only blur the page for a correction the user cannot see.
     * The threshold is `abs(degrees) < 0.08`, and the boundary is what is pinned — just under it the
     * picture is untouched, just over it the picture moves.
     */
    @Test
    fun `a negligible deskew is a no-op`() {
        val source = capture()
        fun previewAt(degrees: Double) = ScanProcessor.preview(
            source,
            ScanProcessor.Settings(deskewDegrees = degrees, filter = ScanFilter.ORIGINAL),
            900,
        )

        val untouched = previewAt(0.0)
        assertSamePixels("0.079 is below the threshold", untouched, previewAt(0.079))
        assertSamePixels("-0.079 is below the threshold", untouched, previewAt(-0.079))

        // Above it the page really is resampled.
        assertNotEquals("0.5 is above the threshold", untouched.pixels.toList(), previewAt(0.5).pixels.toList())
        assertNotEquals("-0.5 is above the threshold", untouched.pixels.toList(), previewAt(-0.5).pixels.toList())
    }
}
