package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The zoom and pan arithmetic every pinchable surface in the app shares.
 *
 * Nothing here touches Compose or a device: [Viewports] is pure arithmetic over screen
 * coordinates, so a plain JVM test can pin the whole of it. The Compose wiring that feeds it
 * ([com.kscanner.ui.components.Modifier.zoomable]) is *not* covered — that needs an emulator.
 *
 * Every assertion below is derived from the model documented on [PreviewViewport]:
 *
 *     screen(p) = center + (p - center) * scale + offset
 *
 * where `center` is the viewport's centre and `p` is a point in the viewport's own (unscaled)
 * coordinates. The helper [screenOf] is that formula written out once, and the tests compare the
 * offsets the library returns against what the formula says they must be, rather than against the
 * library's own algebra.
 *
 * **Off-origin windows are pinned as well.** [Viewports.clamp] anchors the span it must cover to the
 * window's own `left` / `top`, so a [ViewportRect] whose origin is not (0, 0) centres the picture at
 * 1x and keeps a magnified one covering the window exactly as an origin-anchored one does. Every
 * viewport the app constructs is origin-anchored (Zoomable.kt's private `Surface`), so that shape is
 * the one the app runs; both shapes are covered below.
 */
class PreviewViewportTest {

    private companion object {
        /** Float noise floor for assertions whose exact value is not provably representable. */
        const val EPS = 1e-4f

        fun vessel(left: Float, top: Float, width: Float, height: Float) =
            ViewportRect(left, top, width, height)

        /** The window used by most cases: portrait, and not square. */
        val PORTRAIT = vessel(0f, 0f, 400f, 800f)
    }

    /**
     * The documented model, written out once: where the viewport-space point [p] is drawn.
     *
     * `screen = center + (p - center) * scale + offset`.
     */
    private fun screenOf(viewport: ViewportRect, p: Float, axisX: Boolean, state: PreviewViewport): Float {
        val center = if (axisX) viewport.centerX else viewport.centerY
        val offset = if (axisX) state.offsetX else state.offsetY
        return center + (p - center) * state.scale + offset
    }

    /** Where the scaled content's leading (or trailing) edge is drawn, from the same model. */
    private fun drawnEdge(
        viewport: ViewportRect,
        content: ViewportRect,
        axisX: Boolean,
        trailing: Boolean,
        state: PreviewViewport,
    ): Float {
        val center = if (axisX) viewport.centerX else viewport.centerY
        val start = if (axisX) content.left else content.top
        val span = if (axisX) content.width else content.height
        val leading = center + (start - center) * state.scale
        val edge = if (trailing) leading + state.scale * span else leading
        return edge + (if (axisX) state.offsetX else state.offsetY)
    }

    // ------------------------------------------------------------------ fitRect

    /**
     * The three aspect regimes, with the exact rectangle each one must produce.
     *
     * The fitted box is `min(viewportWidth, viewportHeight * aspect)` wide, so a picture wider
     * than its window is limited by the width and one narrower than its window by the height.
     */
    @Test
    fun `fitRect picks the limiting edge and centres the box`() {
        // Wider than the window (window aspect is 400/800 = 0.5): limited by the width, and the
        // slack goes to the vertical margins. 400/2 = 200 tall, (800 - 200) / 2 = 300 from the top.
        assertEquals(
            "wider than the window",
            ViewportRect(0f, 300f, 400f, 200f),
            Viewports.fitRect(PORTRAIT, 2f),
        )

        // Narrower than the window: limited by the height, slack split horizontally.
        // 800 * 0.25 = 200 wide, (400 - 200) / 2 = 100 from the left.
        assertEquals(
            "narrower than the window",
            ViewportRect(100f, 0f, 200f, 800f),
            Viewports.fitRect(PORTRAIT, 0.25f),
        )

        // Exactly the window's aspect: it fills the window on both axes.
        assertEquals(
            "exactly the window's aspect",
            ViewportRect(0f, 0f, 400f, 800f),
            Viewports.fitRect(PORTRAIT, 0.5f),
        )
    }

    /** Centred, correctly proportioned, touching two opposite edges, and inside the window. */
    @Test
    fun `fitRect keeps the picture centred, in proportion and within the window`() {
        val viewports = listOf(
            PORTRAIT,
            vessel(0f, 0f, 800f, 400f),
            vessel(0f, 0f, 500f, 500f),
            vessel(30f, -20f, 333f, 777f),
            vessel(-100f, -100f, 1000f, 1000f),
        )
        val aspects = listOf(0.05f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3.7f, 100f)

        for (viewport in viewports) {
            for (aspect in aspects) {
                val rect = Viewports.fitRect(viewport, aspect)
                val where = "viewport $viewport aspect $aspect"

                assertEquals("$where: horizontal centre", viewport.centerX, rect.centerX, EPS)
                assertEquals("$where: vertical centre", viewport.centerY, rect.centerY, EPS)
                assertEquals("$where: aspect kept", aspect, rect.width / rect.height, EPS)

                // Inside the window on all four sides.
                assertTrue("$where: left edge $rect", rect.left >= viewport.left - EPS)
                assertTrue("$where: top edge $rect", rect.top >= viewport.top - EPS)
                assertTrue(
                    "$where: right edge $rect",
                    rect.left + rect.width <= viewport.left + viewport.width + EPS,
                )
                assertTrue(
                    "$where: bottom edge $rect",
                    rect.top + rect.height <= viewport.top + viewport.height + EPS,
                )

                // Touches two opposite edges: the one the aspect is limited by, or both when the
                // aspect is exactly the window's.
                val touchesHorizontally = abs(rect.left - viewport.left) <= EPS &&
                    abs(rect.left + rect.width - (viewport.left + viewport.width)) <= EPS
                val touchesVertically = abs(rect.top - viewport.top) <= EPS &&
                    abs(rect.top + rect.height - (viewport.top + viewport.height)) <= EPS
                assertTrue("$where: touches no pair of opposite edges, rect $rect", touchesHorizontally || touchesVertically)
            }
        }
    }

    /**
     * Degenerate requests collapse to an empty box at the window's centre. That is the answer
     * [Viewports.clamp] reduces to "centred, no offset", which is what an unmeasured layout or a
     * sizeless picture has to see.
     */
    @Test
    fun `fitRect degenerates to an empty box in the middle`() {
        assertEquals(ViewportRect(200f, 400f, 0f, 0f), Viewports.fitRect(PORTRAIT, 0f))
        assertEquals(ViewportRect(200f, 400f, 0f, 0f), Viewports.fitRect(PORTRAIT, -1f))

        // A window that has not been measured yet: width, height, or both.
        assertEquals(ViewportRect(50f, 70f, 0f, 0f), Viewports.fitRect(vessel(50f, 20f, 0f, 100f), 1f))
        assertEquals(ViewportRect(200f, 0f, 0f, 0f), Viewports.fitRect(vessel(0f, 0f, 400f, 0f), 1f))
        assertEquals(ViewportRect(0f, 0f, 0f, 0f), Viewports.fitRect(vessel(0f, 0f, 0f, 0f), 0.5f))
    }

    // ------------------------------------------------------------------ clamp

    /**
     * At 1x the picture fits exactly, so both clamp ranges collapse onto zero and the offsets come
     * back at zero — the property `MIN_SCALE` rests on, with no special case for `scale == 1`
     * anywhere in the code. The precondition is the documented one: [content] is the picture's own
     * box at 1x, as [Viewports.fitRect] returns it, not an arbitrary rectangle.
     *
     * The claim is exact for these pairs because the fitted box is *bit-exactly* inside the window
     * for each of them — a viewport whose fitted height lands on a representable value, so
     * `scale * contentHeight <= viewportHeight` holds literally at 1x and [Viewports.clamp] takes
     * its "centred, no offset" branch on both axes. That was confirmed by running the production
     * code over this grid, not assumed: every combination below returns a bit-zero offset from a
     * far-outside request. The companion test below covers the pairs that are not bit-exact.
     */
    @Test
    fun `clamp returns exactly no offset at scale 1`() {
        // The viewports here all start at the origin, which is the only shape the app builds:
        // Zoomable.kt's private Surface is ViewportRect(0f, 0f, width, height). The same claim for
        // windows placed off the origin is pinned by
        // `clamp centres the picture in an off-origin window at scale 1`.
        val viewports = listOf(
            PORTRAIT,
            vessel(0f, 0f, 800f, 400f),
            vessel(0f, 0f, 500f, 500f),
            vessel(0f, 0f, 256f, 512f),
            vessel(0f, 0f, 64f, 64f),
        )
        val aspects = listOf(0.25f, 0.5f, 1f, 2f, 4f)

        for (viewport in viewports) {
            for (aspect in aspects) {
                val content = Viewports.fitRect(viewport, aspect)
                // Ragged starting offsets, including far outside the window.
                for (offset in listOf(-12345.5f, -7.25f, 0f, 7.25f, 12345.5f)) {
                    val clamped = Viewports.clamp(
                        PreviewViewport(1f, offset, -offset),
                        viewport,
                        content,
                    )
                    val where = "viewport $viewport aspect $aspect offset $offset"
                    assertEquals("$where: scale", 1f, clamped.scale, 0f)
                    assertEquals("$where: offsetX", 0f, clamped.offsetX, 0f)
                    assertEquals("$where: offsetY", 0f, clamped.offsetY, 0f)
                }
            }
        }
    }

    /**
     * The rest of the 1x claim, for rectangles that are not bit-exact — and how big the exception
     * can get.
     *
     * [Viewports.fitRect] derives the fitted height as `width / aspect`, which is not exactly
     * representable for most aspects, so a box can come out a float ULP or so taller than the
     * window it was fitted to. At 1x the clamp then measures a scaled span a hair over the window
     * and trims the offset to the midpoint of that sliver instead of returning zero outright.
     * The worst case in this grid is viewport 333x777 with aspect 0.2: the box is 6.1035156E-5
     * (one float ULP at 777) too tall and the offset comes back 3.0517578E-5, half of it — a
     * 30000th of a pixel on screen.
     *
     * An eighth of a pixel is asserted, two orders of magnitude above the few-times-10^-5 that is
     * actually measured, because the point is that the leak stays invisible, not that it equals a
     * particular number. A change that turned it into a visible shift fails here.
     */
    @Test
    fun `clamp returns at most a fraction of a pixel of offset at scale 1`() {
        val viewports = listOf(
            vessel(0f, 0f, 333f, 777f),
            vessel(0f, 0f, 1920f, 1080f),
            vessel(0f, 0f, 1000f, 1000f),
            vessel(0f, 0f, 333f, 333f),
        )
        val aspects = listOf(0.1f, 0.2f, 0.333f, 0.7f, 1.1f, 1.7f, 3f, 3.3f)
        val slack = 1f / 8f

        for (viewport in viewports) {
            for (aspect in aspects) {
                val content = Viewports.fitRect(viewport, aspect)
                for (offset in listOf(-12345.5f, -7.25f, 0f, 7.25f, 12345.5f)) {
                    val clamped = Viewports.clamp(
                        PreviewViewport(1f, offset, -offset),
                        viewport,
                        content,
                    )
                    val where = "viewport $viewport aspect $aspect offset $offset"
                    assertEquals("$where: scale", 1f, clamped.scale, 0f)
                    assertTrue(
                        "$where: offsetX ${clamped.offsetX} leaked past $slack",
                        abs(clamped.offsetX) <= slack,
                    )
                    assertTrue(
                        "$where: offsetY ${clamped.offsetY} leaked past $slack",
                        abs(clamped.offsetY) <= slack,
                    )
                }
            }
        }
    }

    /**
     * Once the picture is bigger than the window it must keep covering it; once it is smaller it is
     * centred. Both halves of that are checked against the model, not against the clamp's algebra:
     * the edges are computed from `center + (p - center) * scale + offset`.
     */
    @Test
    fun `clamp keeps a magnified picture covering the window`() {
        // Origin-anchored windows here; the off-origin shape of the same claim is pinned by the two
        // tests below, whose grids run over windows that do not start at (0, 0).
        val viewports = listOf(PORTRAIT, vessel(0f, 0f, 800f, 400f), vessel(0f, 0f, 250f, 250f), vessel(0f, 0f, 640f, 360f))
        val aspects = listOf(0.5f, 1f, 1.6f, 2.5f)
        val scales = listOf(1f, 1.3f, 2f, 3.7f, 5f)
        val offsets = listOf(-1e5f, -1234.5f, -3.25f, 0f, 3.25f, 1234.5f, 1e5f)

        var covered = 0
        var centred = 0

        for (viewport in viewports) {
            for (aspect in aspects) {
                val content = Viewports.fitRect(viewport, aspect)
                for (scale in scales) {
                    for (offset in offsets) {
                        val state = Viewports.clamp(
                            PreviewViewport(scale, offset, offset / 3f),
                            viewport,
                            content,
                        )
                        val where = "viewport $viewport aspect $aspect scale $scale offset $offset"

                        assertEquals("$where: scale never moves", scale, state.scale, 0f)

                        for (axisX in listOf(true, false)) {
                            val windowSpan = if (axisX) viewport.width else viewport.height
                            val windowStart = if (axisX) viewport.left else viewport.top
                            val scaledSpan = state.scale * (if (axisX) content.width else content.height)
                            val offsetNow = if (axisX) state.offsetX else state.offsetY

                            if (scaledSpan > windowSpan) {
                                val leading = drawnEdge(viewport, content, axisX, trailing = false, state = state)
                                val trailing = drawnEdge(viewport, content, axisX, trailing = true, state = state)
                                assertTrue(
                                    "$where: leading edge $leading is inside the window at $windowStart",
                                    leading <= windowStart + 1e-2f,
                                )
                                assertTrue(
                                    "$where: trailing edge $trailing does not reach ${windowStart + windowSpan}",
                                    trailing >= windowStart + windowSpan - 1e-2f,
                                )
                                covered++
                            } else {
                                assertEquals("$where: smaller axis is centred", 0f, offsetNow, 0f)
                                centred++
                            }
                        }
                    }
                }
            }
        }

        // Both branches really were taken, so neither half of the claim is vacuous.
        assertTrue("no covering case was exercised", covered > 100)
        assertTrue("no centring case was exercised", centred > 10)
    }

    /**
     * The 1x claim for a window that does not start at the origin: the fitted picture is centred on
     * the window's own centre, not on (0, 0) nor on its own untranslated coordinates.
     *
     * At 1x the model reduces to `screen(p) = p + offset`, and [Viewports.fitRect] has already put
     * the content's centre on the window's centre, so the offset must be zero for the drawn centre
     * to stay there. The pairs below are picked so the fitted box is bit-exactly inside the window —
     * every aspect is a power of two, so `width / aspect` is exact — which is what lets the offsets
     * be asserted at literally zero on both axes.
     */
    @Test
    fun `clamp centres the picture in an off-origin window at scale 1`() {
        val viewports = listOf(
            vessel(20f, -30f, 640f, 360f),
            vessel(30f, -20f, 333f, 777f),
            vessel(-100f, -100f, 1000f, 1000f),
            vessel(1000f, 500f, 400f, 800f),
        )
        val aspects = listOf(0.25f, 0.5f, 1f, 2f, 4f)
        val offsets = listOf(-12345.5f, -7.25f, 0f, 7.25f, 12345.5f)

        for (viewport in viewports) {
            for (aspect in aspects) {
                val content = Viewports.fitRect(viewport, aspect)
                for (offset in offsets) {
                    val state = Viewports.clamp(PreviewViewport(1f, offset, -offset), viewport, content)
                    val where = "viewport $viewport aspect $aspect offset $offset"

                    assertEquals("$where: scale", 1f, state.scale, 0f)
                    assertEquals("$where: offsetX", 0f, state.offsetX, 0f)
                    assertEquals("$where: offsetY", 0f, state.offsetY, 0f)

                    for (axisX in listOf(true, false)) {
                        val windowStart = if (axisX) viewport.left else viewport.top
                        val windowSpan = if (axisX) viewport.width else viewport.height
                        val windowCentre = if (axisX) viewport.centerX else viewport.centerY
                        val contentCentre = if (axisX) content.centerX else content.centerY

                        // The picture's own centre, from the model, lands on the window's centre.
                        assertEquals(
                            "$where: drawn centre",
                            windowCentre,
                            screenOf(viewport, contentCentre, axisX, state),
                            EPS,
                        )
                        // And it is still inside the window on that axis, not cropped by it.
                        val leading = drawnEdge(viewport, content, axisX, trailing = false, state = state)
                        val trailing = drawnEdge(viewport, content, axisX, trailing = true, state = state)
                        assertTrue(
                            "$where: overhangs the leading edge ($leading < $windowStart)",
                            leading >= windowStart - EPS,
                        )
                        assertTrue(
                            "$where: overhangs the trailing edge ($trailing > ${windowStart + windowSpan})",
                            trailing <= windowStart + windowSpan + EPS,
                        )
                    }
                }
            }
        }

        // The same claim where the fitted box is *not* bit-exact, so the clamp really does clamp at
        // 1x instead of taking its "centred" branch. `width / aspect` leaves the box a float ULP or
        // two taller than the window (6.1E-5 for the 777-tall window below), and then the offset is
        // the midpoint of that sliver — a few times 10^-5. What it must never be is the window's
        // origin, which is what a "must cover 0..span" reading of the window produces: -20f, -30f
        // and -100f here, i.e. a 20/30/100 px shift.
        val ragged = listOf(
            vessel(30f, -20f, 333f, 777f) to 0.2f,
            vessel(20f, -30f, 640f, 500f) to 1.3f,
            vessel(-100f, -100f, 1000f, 1000f) to 1.3f,
        )
        for ((viewport, aspect) in ragged) {
            val content = Viewports.fitRect(viewport, aspect)
            for (offset in offsets) {
                val state = Viewports.clamp(PreviewViewport(1f, offset, -offset), viewport, content)
                val where = "viewport $viewport aspect $aspect offset $offset"
                for (axisX in listOf(true, false)) {
                    val windowCentre = if (axisX) viewport.centerX else viewport.centerY
                    val contentCentre = if (axisX) content.centerX else content.centerY
                    assertEquals(
                        "$where: drawn centre stays on the window's centre",
                        windowCentre,
                        screenOf(viewport, contentCentre, axisX, state),
                        1e-3f,
                    )
                }
            }
        }
    }

    /**
     * The covering claim for a window that does not start at the origin, including the case that
     * exposed the defect: viewport `(20, -30, 640, 360)`, aspect 0.5, scale 1.3 and a 411.5 px push,
     * where the picture came to rest with its top edge on y = 0 — a 30 px gap above it, because the
     * window starts at -30.
     *
     * The edges come from [drawnEdge], i.e. from the documented model, so the assertions say "the
     * window is covered", not "some offset came back".
     */
    @Test
    fun `clamp keeps a magnified picture covering an off-origin window`() {
        val viewports = listOf(
            vessel(20f, -30f, 640f, 360f),
            vessel(30f, -20f, 333f, 777f),
            vessel(-100f, -100f, 1000f, 1000f),
        )
        val aspects = listOf(0.5f, 1f, 1.6f, 2.5f)
        val scales = listOf(1f, 1.3f, 2f, 3.7f, 5f)
        val offsets = listOf(-1e5f, -411.5f, -3.25f, 0f, 3.25f, 411.5f, 1e5f)

        var covered = 0

        for (viewport in viewports) {
            for (aspect in aspects) {
                val content = Viewports.fitRect(viewport, aspect)
                for (scale in scales) {
                    for (offset in offsets) {
                        val state = Viewports.clamp(
                            PreviewViewport(scale, offset, offset / 3f),
                            viewport,
                            content,
                        )
                        val where = "viewport $viewport aspect $aspect scale $scale offset $offset"

                        assertEquals("$where: scale never moves", scale, state.scale, 0f)

                        for (axisX in listOf(true, false)) {
                            val windowStart = if (axisX) viewport.left else viewport.top
                            val windowSpan = if (axisX) viewport.width else viewport.height
                            val scaledSpan = state.scale * (if (axisX) content.width else content.height)
                            if (scaledSpan <= windowSpan) {
                                assertEquals(
                                    "$where: smaller axis is centred",
                                    0f,
                                    if (axisX) state.offsetX else state.offsetY,
                                    0f,
                                )
                                continue
                            }
                            val leading = drawnEdge(viewport, content, axisX, trailing = false, state = state)
                            val trailing = drawnEdge(viewport, content, axisX, trailing = true, state = state)
                            assertTrue(
                                "$where: gap at the leading edge ($leading > $windowStart)",
                                leading <= windowStart + 1e-2f,
                            )
                            assertTrue(
                                "$where: gap at the trailing edge ($trailing < ${windowStart + windowSpan})",
                                trailing >= windowStart + windowSpan - 1e-2f,
                            )
                            covered++
                        }
                    }
                }
            }
        }

        assertTrue("no covering case was exercised", covered > 100)

        // The measured case, at its own numbers: a 640x360 window 20 px right and 30 px up from the
        // origin, the window's own aspect so the picture fills the window's height exactly.
        val viewport = vessel(20f, -30f, 640f, 360f)
        val content = Viewports.fitRect(viewport, 0.5f)
        assertEquals(
            "the fitted picture is 640/2 * 0.5 = 180 wide and as tall as the window",
            ViewportRect(250f, -30f, 180f, 360f),
            content,
        )

        val zoomed = PreviewViewport(1.3f, 0f, 411.5f)
        val clamped = Viewports.clamp(zoomed, viewport, content)

        // Where the model draws the picture's top edge with no offset at all.
        val unpushed = drawnEdge(viewport, content, axisX = false, trailing = false, state = PreviewViewport(1.3f, 0f, 0f))
        // The push stops where that edge meets the window's top edge, so this is the offset the
        // model demands — 54f here, not the 84f an origin-anchored span would leave it at.
        assertEquals("the push stops at the window's own top edge", viewport.top - unpushed, clamped.offsetY, EPS)
        assertEquals("the picture's top edge is the window's top edge", viewport.top,
            drawnEdge(viewport, content, axisX = false, trailing = false, state = clamped), EPS)
        // 1.3 x 360 = 468 tall from -30, so the bottom of the window is still covered.
        assertTrue(
            "the picture still reaches the window's bottom edge",
            drawnEdge(viewport, content, axisX = false, trailing = true, state = clamped) >= 330f - 1e-2f,
        )
        // Horizontally 1.3 x 180 = 234 of 640, so that axis is letterboxed and stays centred.
        assertEquals("the letterboxed axis stays centred", 0f, clamped.offsetX, 0f)
        assertEquals("the picture's centre is the window's centre horizontally", viewport.centerX,
            screenOf(viewport, content.centerX, axisX = true, state = clamped), EPS)
    }

    // ------------------------------------------------------------------ gesture

    /**
     * The pinch anchor, with the invariant written out.
     *
     * Invariant: with no pan, the content point that was drawn under the fingers is still drawn
     * there after the pinch. With a pan, it ends up displaced by exactly that pan. In symbols, for
     * a content point `p` drawn at `f` before the gesture:
     *
     *     after = gesture(current, zoom, panX, panY, f.x, f.y, viewport, content)
     *     screen'(p) = f + pan
     *
     * The state below is deliberately at 2x with the picture filling the window, so the offsets the
     * anchor wants are well inside the clamp range and nothing interferes with the measurement.
     */
    @Test
    fun `gesture keeps the point under the fingers under them`() {
        val viewport = PORTRAIT
        // aspect 0.5 is exactly the window's, so the content fills it: the clamp is active on both
        // axes from 2x on, which is the regime a pinch actually happens in.
        val content = Viewports.fitRect(viewport, 0.5f)
        val current = PreviewViewport(2f, 0f, 0f)

        // A content point, and the screen position it is drawn at before the gesture.
        val px = 175f
        val py = 325f
        val fx = screenOf(viewport, px, axisX = true, state = current)
        val fy = screenOf(viewport, py, axisX = false, state = current)
        assertEquals("the probe point starts where the model says", 150f, fx, EPS)
        assertEquals("the probe point starts where the model says", 250f, fy, EPS)

        // A pure pinch: it must leave the point exactly under the fingers.
        val pinched = Viewports.gesture(current, 1.25f, 0f, 0f, fx, fy, viewport, content)
        assertEquals("2 x 1.25", 2.5f, pinched.scale, EPS)
        assertEquals("anchored horizontally", fx, screenOf(viewport, px, axisX = true, state = pinched), EPS)
        assertEquals("anchored vertically", fy, screenOf(viewport, py, axisX = false, state = pinched), EPS)

        // The same pinch with a pan: the point follows the fingers, offset by exactly the pan.
        val dragged = Viewports.gesture(current, 1.25f, 20f, -30f, fx, fy, viewport, content)
        assertEquals("anchored, then panned horizontally", fx + 20f, screenOf(viewport, px, axisX = true, state = dragged), EPS)
        assertEquals("anchored, then panned vertically", fy - 30f, screenOf(viewport, py, axisX = false, state = dragged), EPS)
    }

    /**
     * A pinch about the window's own centre, from a centred state, leaves the offsets exactly at
     * zero: `(1 - ratio) * (focus - center)` is a product with a zero factor on both axes, and the
     * clamp's range is symmetric about zero whenever the picture covers the window.
     */
    @Test
    fun `a centre pinch from a centred state leaves no offset`() {
        val viewports = listOf(PORTRAIT, vessel(0f, 0f, 800f, 400f), vessel(0f, 0f, 250f, 250f))
        val aspects = listOf(0.5f, 1f, 1.6f, 2.5f)

        for (viewport in viewports) {
            for (aspect in aspects) {
                val content = Viewports.fitRect(viewport, aspect)
                for (zoom in listOf(1.2f, 1.7f, 2f)) {
                    val next = Viewports.gesture(
                        current = PreviewViewport(2f, 0f, 0f),
                        zoom = zoom,
                        panX = 0f,
                        panY = 0f,
                        focusX = viewport.centerX,
                        focusY = viewport.centerY,
                        viewport = viewport,
                        content = content,
                    )
                    val where = "viewport $viewport aspect $aspect zoom $zoom"
                    assertEquals("$where: scale", 2f * zoom, next.scale, EPS)
                    assertEquals("$where: offsetX", 0f, next.offsetX, 0f)
                    assertEquals("$where: offsetY", 0f, next.offsetY, 0f)
                }
            }
        }
    }

    /**
     * A pan at ratio 1 adds exactly the pan while the clamp has room for it — and when the pan
     * runs past that room, the result is the pan and then the clamp, nothing else. Both halves are
     * checked, the second against [Viewports.clamp] itself so the boundary is measured rather than
     * assumed.
     */
    @Test
    fun `a pure pan adds the pan until the clamp stops it`() {
        val viewport = PORTRAIT
        // The window's own aspect, so the picture fills it and both axes stay clamped at 2x.
        val content = Viewports.fitRect(viewport, 0.5f)
        val current = PreviewViewport(2f, 0f, 0f)

        // Room to spare: the offset is the pan, exactly.
        val moved = Viewports.gesture(current, 1f, 60f, -80f, viewport.centerX, viewport.centerY, viewport, content)
        assertEquals("scale unchanged", 2f, moved.scale, 0f)
        assertEquals("the pan, untouched", 60f, moved.offsetX, 0f)
        assertEquals("the pan, untouched", -80f, moved.offsetY, 0f)

        // Past the edge: the picture stops at its own boundary, which is what clamp decides.
        val pushed = Viewports.gesture(current, 1f, 500f, 0f, viewport.centerX, viewport.centerY, viewport, content)
        val expected = Viewports.clamp(PreviewViewport(2f, 500f, 0f), viewport, content)
        assertEquals("clamped horizontally", expected.offsetX, pushed.offsetX, 0f)
        assertEquals("and the picture still covers the window", viewport.left,
            drawnEdge(viewport, content, axisX = true, trailing = false, state = pushed), EPS)

        // A pan with no room left at all must not move the picture.
        val atEdge = Viewports.clamp(PreviewViewport(2f, 1e6f, 0f), viewport, content)
        assertEquals("the edge is where clamp says", pushed.offsetX, atEdge.offsetX, 0f)
    }

    // ------------------------------------------------------------------ scale bounds

    /** Scale is always inside `[MIN_SCALE, MAX_SCALE]`, whatever zoom is asked for. */
    @Test
    fun `scale never leaves its bounds`() {
        val viewport = PORTRAIT
        val content = Viewports.fitRect(viewport, 1f)

        for (start in listOf(1f, 1.5f, 3f, 5f)) {
            for (zoom in listOf(-2f, 0f, 0.001f, 0.5f, 1f, 1.4f, 12f, 1e6f)) {
                val next = Viewports.gesture(PreviewViewport(start, 0f, 0f), zoom, 0f, 0f, 100f, 100f, viewport, content)
                val where = "start $start zoom $zoom"
                assertTrue("$where: scale ${next.scale} fell below ${Viewports.MIN_SCALE}", next.scale >= Viewports.MIN_SCALE)
                assertTrue("$where: scale ${next.scale} rose above ${Viewports.MAX_SCALE}", next.scale <= Viewports.MAX_SCALE)
            }
        }

        // The same bound holds for a state handed straight to clamp.
        assertEquals(Viewports.MIN_SCALE, Viewports.clamp(PreviewViewport(0.2f), viewport, content).scale, 0f)
        assertEquals(Viewports.MAX_SCALE, Viewports.clamp(PreviewViewport(99f), viewport, content).scale, 0f)
    }

    /**
     * Pinching far past `MAX_SCALE` in one step leaves the picture where it is.
     *
     * This is the reason the focus is resolved against the zoom that *took effect* rather than the
     * one that was asked for: with the requested zoom the offsets would be rescaled by 4 while the
     * picture only grew to the cap, and the page would jump out from under the fingers. Here the
     * pinch asks for 4x from a state already at the cap, so the effective ratio is 1 and no anchor
     * correction may be applied at all.
     */
    @Test
    fun `a pinch past the cap does not move the picture`() {
        val viewport = PORTRAIT
        val content = Viewports.fitRect(viewport, 1f)

        // A centred state at the cap: whatever the clamp does, it does the same to both.
        for (offset in listOf(0f, 40f, -40f)) {
            val current = PreviewViewport(Viewports.MAX_SCALE, offset, offset)
            val next = Viewports.gesture(
                current = current,
                zoom = 4f,
                panX = 0f,
                panY = 0f,
                focusX = 120f, // well away from the centre: a stale ratio would drag the page here
                focusY = 640f,
                viewport = viewport,
                content = content,
            )
            val where = "offset $offset"
            assertEquals("$where: still at the cap", Viewports.MAX_SCALE, next.scale, 0f)
            assertEquals("$where: offsetX unchanged", current.offsetX, next.offsetX, 0f)
            assertEquals("$where: offsetY unchanged", current.offsetY, next.offsetY, 0f)
        }
    }

    // ------------------------------------------------------------------ doubleTap

    /**
     * A double tap at 1x magnifies to exactly 2x around the tap: the tap point itself must not
     * move. The tap below is off centre but far enough from the edge that the anchor wants an
     * offset the clamp has room for, so the value is exact.
     */
    @Test
    fun `a double tap at 1x magnifies around the tap point`() {
        val viewport = PORTRAIT
        // The window's own aspect: the picture fills it, so both axes stay clamped at 2x and the
        // anchor the tap asks for is not trimmed. For the same reason the assertions below are
        // exact rather than approximate.
        val content = Viewports.fitRect(viewport, 0.5f)

        val tapX = 100f
        val tapY = 400f
        val next = Viewports.doubleTap(PreviewViewport(), tapX, tapY, viewport, content)

        assertEquals("double tap scale", 2f, next.scale, 0f)
        // (1 - 2) * (tap - center): 200 - 100 = 100 to the right, and no vertical shift because
        // the tap is on the window's horizontal centre line.
        assertEquals("anchored horizontally", 100f, next.offsetX, 0f)
        assertEquals("anchored vertically", 0f, next.offsetY, 0f)

        // And the tap point is still under the tap, via the model.
        assertEquals("the tap point stayed put", tapX, screenOf(viewport, tapX, axisX = true, state = next), EPS)
        assertEquals("the tap point stayed put", tapY, screenOf(viewport, tapY, axisX = false, state = next), EPS)

        // A tap on the other side anchors the other way.
        val left = Viewports.doubleTap(PreviewViewport(), 40f, 700f, viewport, content)
        assertEquals("anchored the other way", 160f, left.offsetX, 0f)
        assertEquals("anchored vertically", -300f, left.offsetY, 0f)
        assertEquals("the tap point stayed put", 40f, screenOf(viewport, 40f, axisX = true, state = left), EPS)
        assertEquals("the tap point stayed put", 700f, screenOf(viewport, 700f, axisX = false, state = left), EPS)
    }

    /** Once magnified, a double tap drops straight back to 1x in the middle of the window. */
    @Test
    fun `a double tap above the threshold returns to 1x with no offset`() {
        val viewport = PORTRAIT
        val content = Viewports.fitRect(viewport, 1f)

        for (scale in listOf(1.03f, 1.5f, 2f, 5f)) {
            val zoomed = PreviewViewport(scale, 123f, -456f)
            val next = Viewports.doubleTap(zoomed, 310f, 90f, viewport, content)
            val where = "scale $scale"
            assertEquals("$where: back to natural size", 1f, next.scale, 0f)
            assertEquals("$where: centred", 0f, next.offsetX, 0f)
            assertEquals("$where: centred", 0f, next.offsetY, 0f)
        }

        // The threshold itself is exclusive: at or below it the tap magnifies instead. 2/1.02 is
        // not exactly representable, so the direction is asserted rather than the exact value.
        val atThreshold = Viewports.doubleTap(PreviewViewport(1.02f, 0f, 0f), 100f, 400f, viewport, content)
        assertTrue(
            "at the threshold a double tap still magnifies, was ${atThreshold.scale}",
            atThreshold.scale > 1.5f,
        )
    }

    /** Repeated double taps alternate between the natural size and the magnified one. */
    @Test
    fun `repeated double taps return to 1x`() {
        val viewport = PORTRAIT
        val content = Viewports.fitRect(viewport, 0.5f)
        val tapX = 100f
        val tapY = 400f

        var state = PreviewViewport()
        val seen = ArrayList<Float>()
        repeat(4) {
            state = Viewports.doubleTap(state, tapX, tapY, viewport, content)
            seen.add(state.scale)
        }

        assertEquals("in at the tap, out to the middle, twice", listOf(2f, 1f, 2f, 1f), seen)
        assertEquals("the last state is the natural size", 0f, state.offsetX, 0f)
        assertEquals("the last state is the natural size", 0f, state.offsetY, 0f)
    }

    // ------------------------------------------------------------------ composite

    /**
     * A gesture stream keeps the documented shape: whatever it produces can be fed straight back in
     * (scale in bounds), and the picture never leaves the window behind.
     */
    @Test
    fun `a feed of gestures keeps the state inside the contract`() {
        val viewport = vessel(0f, 0f, 400f, 800f)
        val content = Viewports.fitRect(viewport, 0.75f)
        val zooms = listOf(1.6f, 1.1f, 0.55f, 1.4f, 3f, 0.9f, 0.2f, 1.05f)
        val pans = listOf(0f to 0f, 25f to -40f, -900f to 900f, 3f to 3f, 0f to 700f)

        var state = PreviewViewport()
        for (i in zooms.indices) {
            val (panX, panY) = pans[i % pans.size]
            state = Viewports.gesture(
                current = state,
                zoom = zooms[i],
                panX = panX,
                panY = panY,
                focusX = 130f + i * 20f,
                focusY = 690f - i * 55f,
                viewport = viewport,
                content = content,
            )

            assertTrue("step $i: scale ${state.scale} out of range", state.scale >= 1f && state.scale <= 5f)
            for (axisX in listOf(true, false)) {
                val windowSpan = if (axisX) viewport.width else viewport.height
                val scaledSpan = state.scale * (if (axisX) content.width else content.height)
                if (scaledSpan <= windowSpan) continue
                val leading = drawnEdge(viewport, content, axisX, trailing = false, state = state)
                val trailing = drawnEdge(viewport, content, axisX, trailing = true, state = state)
                val start = if (axisX) viewport.left else viewport.top
                assertTrue("step $i: gap at the leading edge ($leading > $start)", leading <= start + 1e-2f)
                assertTrue(
                    "step $i: gap at the trailing edge ($trailing < ${start + windowSpan})",
                    trailing >= start + windowSpan - 1e-2f,
                )
            }
        }
    }
}
