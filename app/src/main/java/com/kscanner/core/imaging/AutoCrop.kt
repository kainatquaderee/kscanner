package com.kscanner.core.imaging

import kotlin.math.max

/**
 * One-call automatic preparation of a capture.
 *
 * The ordering matters and is the whole point of this object. The old design guessed the
 * page's orientation from the whole frame, but a photographed page sits on a darker surface,
 * and the adaptive ink mask turns the boundary between paper and table into a thick halo
 * that swamps the text's projection profile. Measuring orientation *inside* the discovered
 * outline removes the halo entirely.
 *
 * So: find the outline first (the detector no longer cares which way up the page is), flatten
 * it, read the orientation and the residual text skew from that clean page, then report the
 * rotation to apply to the whole capture with the outline rotated to match.
 *
 * Kept framework-free so the exact preparation the app runs is the one the tests run.
 */
object AutoCrop {

    data class Plan(
        /** Clockwise rotation that makes the capture upright, in quarter turns. */
        val rotationDegrees: Int,
        /** Outline in pixels of the *rotated* full-resolution image, or null. */
        val quad: Quad?,
        /**
         * The same outline as 0..1 fractions of the rotated frame, or null.
         *
         * Carried alongside [quad] deliberately. The two differ by the frame size, and
         * confusing them silently inflates the outline by the downscale factor and pushes it
         * off the picture. Callers that work in normalised space use this one.
         */
        val quadNormalised: Quad?,
        /** Residual skew of the text line, to remove after cropping. */
        val deskewDegrees: Double,
        val method: DetectMethod?,
        val confidence: Double,
        val quality: Quality.Report,
    )

    fun plan(
        image: RgbaImage,
        workingEdge: Int = 1080,
        aspectHint: Double? = null,
        fixOrientation: Boolean = true,
        detectOutline: Boolean = true,
    ): Plan {
        val preview = Transforms.fitLongestEdge(image, workingEdge)
        val detection = if (detectOutline) {
            DocDetector.detect(preview, aspectHint = aspectHint)
        } else {
            null
        }

        val page: GrayImage = if (detection != null) {
            flatten(preview, detection.quad) ?: preview.toGray()
        } else {
            preview.toGray()
        }

        val orientation = if (fixOrientation) Orientation.estimate(page) else Orientation.UPRIGHT
        val rotation = orientation.rotationDegrees

        // The page, turned upright: what the user will actually look at.
        val uprightPage = if (rotation == 0) page else rotate90(page, rotation / 90)

        // Skew reports the tilt it observed; the correction is the opposite rotation.
        val deskew = -Skew.estimate(uprightPage).degrees

        // After a quarter turn the frame's width and height swap, so the two spaces have to be
        // related through the *rotated* frames — not the originals.
        val rotatedPreviewWidth = if (rotation % 180 == 0) preview.width else preview.height
        val rotatedPreviewHeight = if (rotation % 180 == 0) preview.height else preview.width
        val rotatedImageWidth = if (rotation % 180 == 0) image.width else image.height
        val rotatedImageHeight = if (rotation % 180 == 0) image.height else image.width

        // The outline in the two spaces the callers need: pixels of the rotated full-resolution
        // image, and 0..1 fractions of the rotated frame. Kept as a pair so they can never drift
        // apart — the bug was dividing one by the other's frame.
        //
        // A candidate whose corners leave the frame is a wild guess; keeping it would crop to
        // something the user never pointed at, so drop it and let the caller fall back.
        val outline: Pair<Quad, Quad>? = detection?.quad?.let { found ->
            val pixels = rotateQuad(found, preview.width, preview.height, rotation)
            val fractions = pixels.scaled(1.0 / rotatedPreviewWidth, 1.0 / rotatedPreviewHeight)
            if (insideFrame(fractions)) pixels to fractions else null
        }

        return Plan(
            rotationDegrees = rotation,
            quad = outline?.first?.scaled(
                rotatedImageWidth.toDouble() / rotatedPreviewWidth,
                rotatedImageHeight.toDouble() / rotatedPreviewHeight,
            ),
            quadNormalised = outline?.second,
            deskewDegrees = deskew,
            method = if (outline != null) detection?.method else null,
            confidence = if (outline != null) max(0.0, detection?.confidence ?: 0.0) else 0.0,
            quality = Quality.analyse(uprightPage, hasDocument = outline != null),
        )
    }

    /** True when every corner lies inside the unit square, with a little slack for the edge. */
    private fun insideFrame(quad: Quad, slack: Double = 0.02): Boolean =
        quad.points.all {
            it.x >= -slack && it.x <= 1.0 + slack && it.y >= -slack && it.y <= 1.0 + slack
        }

    /** Flattens the detected outline so only the paper is analysed. */
    private fun flatten(image: RgbaImage, quad: Quad): GrayImage? {
        // Pull the outline in slightly: the detected edge sits on the boundary, so a few
        // pixels of the darker surface behind the page would otherwise be warped in and
        // masquerade as a row of ink along the page's edge.
        val clamped = quad.insetBy(0.05).clampTo(image.width, image.height)
        if (clamped.area() < 1024.0 || !clamped.isConvex()) return null
        return runCatching {
            Homography.warpPerspective(image, clamped, 720).toGray()
        }.getOrNull()
    }

    private fun rotateQuad(quad: Quad, width: Int, height: Int, rotation: Int): Quad {
        if (rotation == 0) return quad
        return when (((rotation % 360) + 360) % 360) {
            90 -> Transforms.rotateQuad90(quad, width, height)
            180 -> {
                val once = Transforms.rotateQuad90(quad, width, height)
                Transforms.rotateQuad90(once, height, width)
            }
            else -> {
                val once = Transforms.rotateQuad90(quad, width, height)
                val twice = Transforms.rotateQuad90(once, height, width)
                Transforms.rotateQuad90(twice, width, height)
            }
        }
    }

    private fun rotate90(src: GrayImage, times: Int): GrayImage {
        var current = src
        repeat(((times % 4) + 4) % 4) { current = Orientation.rotate90(current) }
        return current
    }
}
