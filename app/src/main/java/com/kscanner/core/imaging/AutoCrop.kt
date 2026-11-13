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
        /** Outline in the coordinates of the *rotated* full-resolution image, or null. */
        val quad: Quad?,
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

        val quad = detection?.quad?.let { rotateQuad(it, preview.width, preview.height, rotation) }
        // Skew reports the tilt it observed; the correction is the opposite rotation.
        val deskew = -Skew.estimate(uprightPage).degrees

        val scaleX = image.width.toDouble() / preview.width
        val scaleY = image.height.toDouble() / preview.height

        return Plan(
            rotationDegrees = rotation,
            quad = quad?.scaled(scaleX, scaleY),
            deskewDegrees = deskew,
            method = detection?.method,
            confidence = max(0.0, detection?.confidence ?: 0.0),
            quality = Quality.analyse(uprightPage, hasDocument = detection != null),
        )
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
