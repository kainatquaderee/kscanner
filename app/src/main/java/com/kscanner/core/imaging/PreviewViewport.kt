package com.kscanner.core.imaging

/**
 * A rectangle in whichever space it describes: the window a picture is shown in, or the place
 * the picture itself takes up inside that window.
 *
 * The imaging core is deliberately framework-free, so it cannot borrow the UI toolkit's own
 * rectangle type. Keeping both rectangles in one small type also lets [Viewports] be exercised
 * by plain JVM tests instead of only on a device.
 */
data class ViewportRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val centerX: Float get() = left + width / 2f

    val centerY: Float get() = top + height / 2f
}

/**
 * How a picture sits inside its viewport: [scale] about the viewport's centre, then [offsetX] /
 * [offsetY] in the viewport's own pixels.
 *
 * A picture point `p` lands at `center + (p - center) * scale + offset`, so the offset is *not*
 * scaled — one pixel dragged is one pixel moved, at every zoom level. [scale] is always within
 * [Viewports.MIN_SCALE]..[Viewports.MAX_SCALE]; every value of this type that comes out of
 * [Viewports] holds that invariant, so callers can feed it straight back in.
 */
data class PreviewViewport(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * The zoom and pan rules every pinchable surface in the app shares.
 *
 * They live here, apart from the composable that reads the gestures, because they are pure
 * arithmetic over screen coordinates. The two parts that are easy to get wrong — keeping the
 * point between the fingers pinned while pinching, and never letting the paper slide off the
 * viewport — are therefore testable without a device, and the editor cannot drift away from the
 * viewer's behaviour.
 */
object Viewports {

    /** Zooming below the natural size would only add empty margins, which reads as a bug. */
    const val MIN_SCALE = 1f

    /** Five times is as far as a page preview is worth magnifying before it shows only pixels. */
    const val MAX_SCALE = 5f

    /** What a double tap magnifies to when the picture is at its natural size. */
    const val DOUBLE_TAP_SCALE = 2f

    /** Growth that counts as "already zoomed", so a double tap zooms back out instead. */
    private const val ZOOMED_THRESHOLD = 1.02f

    /**
     * The largest centred box of aspect `width / height` that fits inside [viewport]: where the
     * picture sits at 1x. Callers hand this to [clamp] as the thing that must not be dragged out
     * of view, so panning stops at the picture's own edge rather than at the window's.
     *
     * A degenerate request — the layout has not measured yet, or the picture has no size — returns
     * an empty rect in the middle of the viewport, which [clamp] reduces to "centred, no offset".
     */
    fun fitRect(viewport: ViewportRect, aspect: Float): ViewportRect {
        if (aspect <= 0f || viewport.width <= 0f || viewport.height <= 0f) {
            return ViewportRect(viewport.centerX, viewport.centerY, 0f, 0f)
        }
        val width = minOf(viewport.width, viewport.height * aspect)
        val height = width / aspect
        return ViewportRect(
            left = viewport.left + (viewport.width - width) / 2f,
            top = viewport.top + (viewport.height - height) / 2f,
            width = width,
            height = height,
        )
    }

    /**
     * Applies one step of a pinch or drag: [zoom] scales the picture, [panX] / [panY] move it, and
     * whatever sits under [focusX], [focusY] stays under the user's fingers.
     *
     * The focus is resolved against the zoom that *took effect* rather than the one that was asked
     * for, so pinching on past [MAX_SCALE] leaves the picture where it is instead of drifting.
     */
    fun gesture(
        current: PreviewViewport,
        zoom: Float,
        panX: Float,
        panY: Float,
        focusX: Float,
        focusY: Float,
        viewport: ViewportRect,
        content: ViewportRect,
    ): PreviewViewport {
        val nextScale = (current.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = nextScale / current.scale
        return clamp(
            PreviewViewport(
                scale = nextScale,
                // Mirroring `center + (p - center) * scale + offset` about the focus gives
                // `offset' = ratio * offset + (1 - ratio) * (focus - center)`.
                offsetX = ratio * current.offsetX +
                    (1f - ratio) * (focusX - viewport.centerX) + panX,
                offsetY = ratio * current.offsetY +
                    (1f - ratio) * (focusY - viewport.centerY) + panY,
            ),
            viewport,
            content,
        )
    }

    /**
     * Pulls [state] back inside [viewport]: while the picture is larger than its window it has to
     * keep covering the window, and once it is smaller it is centred.
     *
     * This is also what makes [MIN_SCALE] safe. At 1x the picture fits exactly, so every clamp
     * range collapses onto zero and the offsets come back to zero without a special case for it.
     */
    fun clamp(state: PreviewViewport, viewport: ViewportRect, content: ViewportRect): PreviewViewport {
        val scale = state.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        return PreviewViewport(
            scale = scale,
            offsetX = clampAxis(
                offset = state.offsetX,
                scale = scale,
                center = viewport.centerX,
                windowStart = viewport.left,
                windowSpan = viewport.width,
                contentStart = content.left,
                contentSpan = content.width,
            ),
            offsetY = clampAxis(
                offset = state.offsetY,
                scale = scale,
                center = viewport.centerY,
                windowStart = viewport.top,
                windowSpan = viewport.height,
                contentStart = content.top,
                contentSpan = content.height,
            ),
        )
    }

    /**
     * What a double tap on [tapX], [tapY] should do: magnify around the tap, or — once the picture
     * is already zoomed — drop back to the natural size in the middle of the viewport, which is the
     * quickest way out of a magnified corner.
     */
    fun doubleTap(
        current: PreviewViewport,
        tapX: Float,
        tapY: Float,
        viewport: ViewportRect,
        content: ViewportRect,
    ): PreviewViewport {
        if (current.scale > ZOOMED_THRESHOLD) return clamp(PreviewViewport(), viewport, content)
        return gesture(
            current = current,
            zoom = DOUBLE_TAP_SCALE / current.scale,
            panX = 0f,
            panY = 0f,
            focusX = tapX,
            focusY = tapY,
            viewport = viewport,
            content = content,
        )
    }

    /**
     * One axis of [clamp]. The content's leading edge lands at `center + (start - center) * scale +
     * offset` and its trailing edge one scaled span further along, so requiring the first to be at
     * most the window's leading edge ([windowStart]) and the second at least its trailing edge
     * (`windowStart + windowSpan`) gives the range the offset may live in.
     *
     * The window's origin has to enter through [windowStart] rather than being assumed to be zero:
     * a viewport whose [ViewportRect.left] / [ViewportRect.top] is not the origin would otherwise be
     * measured against the wrong span, and the picture would be free to leave a gap along the edge
     * the window does not start at. Every caller in the app happens to build an origin-anchored
     * viewport, so this changes no result today; it just makes the arithmetic say what it means.
     *
     * While the scaled content is no bigger than the window there is nothing to clamp, and
     * returning zero there is what keeps the letterboxed axis centred at every zoom level — that
     * branch needs no window origin, because [fitRect] already centres the content in the viewport.
     */
    private fun clampAxis(
        offset: Float,
        scale: Float,
        center: Float,
        windowStart: Float,
        windowSpan: Float,
        contentStart: Float,
        contentSpan: Float,
    ): Float {
        val scaledSpan = scale * contentSpan
        if (scaledSpan <= windowSpan) return 0f
        val leadingEdge = (1f - scale) * center + scale * contentStart
        // The bounds are `windowStart + windowSpan - scaledSpan - leadingEdge` and
        // `windowStart - leadingEdge`; the upper one is written as the negation of a difference
        // because for an origin-anchored window (`windowStart == 0f`) that is bit-for-bit the
        // expression this axis used before the origin was threaded through, down to the sign of a
        // zero bound. The two forms only differ where both are zero.
        return offset.coerceIn(
            windowStart + windowSpan - scaledSpan - leadingEdge,
            -(leadingEdge - windowStart),
        )
    }
}
