package com.kscanner.core.imaging

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One analysis frame: the size of the luma buffer, and the clockwise turn the camera says is
 * needed to put it upright for the display (`ImageInfo.rotationDegrees`).
 */
data class PreviewFrame(val width: Int, val height: Int, val rotationDegrees: Int)

/**
 * Where the preview puts an upright frame on screen.
 *
 * The preview view fills itself with the frame without distorting it, so there is a single
 * [scale] for both axes and the frame is centred; whatever does not fit is cropped. Every
 * outline drawn over the preview goes through this same mapping, which is why it is a value
 * the caller can build once per layout change rather than arithmetic buried in the drawing.
 */
data class FrameMap(val scale: Double, val dx: Double, val dy: Double) {

    fun map(p: Pt): Pt = Pt(p.x * scale + dx, p.y * scale + dy)

    fun map(q: Quad): Quad = Quad(map(q.tl), map(q.tr), map(q.br), map(q.bl))

    companion object {
        /** A mapping that leaves coordinates alone, used before the view has been measured. */
        val IDENTITY = FrameMap(1.0, 0.0, 0.0)
    }
}

/**
 * Puts a detected outline where the user can see it.
 *
 * Detection runs on the frame as the camera delivers it; drawing happens in view pixels. Two
 * things sit between them and both are easy to get wrong by a quarter turn or a few percent:
 * the frame is usually stored rotated (a phone's sensor is landscape while the screen is
 * portrait), and the preview fills the view by scaling and cropping rather than letterboxing.
 * This object is the only place either is written down.
 */
object FrameGeometry {

    /** Size of the frame after [orient] has been applied. */
    fun orientedSize(frame: PreviewFrame): Pair<Int, Int> {
        val quarters = quarterTurns(frame.rotationDegrees)
        return if (quarters % 2 == 1) Pair(frame.height, frame.width) else Pair(frame.width, frame.height)
    }

    /**
     * Turns [quad] clockwise by the frame's rotation, so the result is in the coordinates the
     * preview shows: x right, y down, origin at the frame's top-left once upright.
     */
    fun orient(quad: Quad, frame: PreviewFrame): Quad {
        var current = quad
        var width = frame.width
        var height = frame.height
        repeat(quarterTurns(frame.rotationDegrees)) {
            current = Transforms.rotateQuad90(current, width, height)
            val swapped = width
            width = height
            height = swapped
        }
        return current
    }

    /** How the preview lays an upright [frameWidth]x[frameHeight] onto a [viewWidth]x[viewHeight] view. */
    fun fillCenter(frameWidth: Int, frameHeight: Int, viewWidth: Int, viewHeight: Int): FrameMap {
        if (frameWidth <= 0 || frameHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return FrameMap.IDENTITY
        val scale = max(viewWidth.toDouble() / frameWidth, viewHeight.toDouble() / frameHeight)
        return FrameMap(
            scale = scale,
            dx = (viewWidth - frameWidth * scale) / 2.0,
            dy = (viewHeight - frameHeight * scale) / 2.0,
        )
    }

    /**
     * The whole journey: a quad detected on [frame], drawn on a preview that is
     * [viewWidth]x[viewHeight]. Returns `null` for a frame or view with no area to speak of.
     */
    fun toView(quad: Quad, frame: PreviewFrame, viewWidth: Int, viewHeight: Int): Quad {
        val (width, height) = orientedSize(frame)
        val map = fillCenter(width, height, viewWidth, viewHeight)
        return map.map(orient(quad, frame))
    }

    /**
     * A quarter turn count, 0..3. The camera reports 0, 90, 180 or 270; anything else is
     * rounded to the nearest quarter turn rather than rejected, because an overlay drawn at the
     * wrong angle for one frame beats no overlay at all.
     */
    private fun quarterTurns(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        return ((normalized / 90.0).roundToInt()) % 4
    }
}
