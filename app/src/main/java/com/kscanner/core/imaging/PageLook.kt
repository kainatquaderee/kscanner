package com.kscanner.core.imaging

/**
 * The look a page was given — the enhancement preset and the adjustments layered on top of it —
 * as one value the store, the editor and the batch rewrite can all pass around.
 *
 * It is deliberately separate from the page's geometry (outline, rotation, skew). "Apply to all"
 * copies the look and nothing else: the look is the only part of one page that means anything on
 * another, whose frame and outline have nothing in common with it.
 *
 * Framework-free on purpose, like the rest of this package, so the mapping below is testable
 * without a device.
 */
data class PageLook(
    val filter: ScanFilter = ScanFilter.MAGIC_COLOR,
    val strength: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val sharpness: Float = 0f,
) {

    /**
     * The one mapping from a stored look to the processor's settings: saving an edit, copying a
     * look across a document and rotating a page all come through here, so the same preset can
     * never be rendered at one strength by one path and a different strength by the next.
     *
     * [quad] is in pixels of the rotated source (or null for the whole frame), [rotationDegrees]
     * and [deskewDegrees] are the page's own geometry and not part of the look — they are passed
     * in because [ScanProcessor.Settings] carries both halves.
     *
     * The adjustments are clamped to the range the sliders offer. Values arrive from the database
     * and from a running editor, and a stored value outside the range would otherwise reach the
     * pixel pipeline unchecked.
     */
    fun toSettings(
        quad: Quad?,
        rotationDegrees: Int,
        deskewDegrees: Double,
        maxOutputEdge: Int,
    ): ScanProcessor.Settings = ScanProcessor.Settings(
        quad = quad,
        rotationDegrees = rotationDegrees,
        deskewDegrees = deskewDegrees,
        filter = filter,
        strength = strength.coerceIn(0f, 1f),
        brightness = brightness.coerceIn(-1f, 1f),
        contrast = contrast.coerceIn(-1f, 1f),
        sharpness = sharpness.coerceIn(0f, 1f),
        maxOutputEdge = maxOutputEdge,
    )
}

/**
 * The look described by these settings.
 *
 * The inverse of [PageLook.toSettings], for the paths that produce a rendered page from a
 * [ScanProcessor.Settings] and then have to store what it was rendered at. Keeping it here means
 * the two halves of the mapping are read together and cannot drift apart.
 */
fun ScanProcessor.Settings.toLook(): PageLook = PageLook(
    filter = filter,
    strength = strength,
    brightness = brightness,
    contrast = contrast,
    sharpness = sharpness,
)

/**
 * This outline expressed in pixels of a [width] x [height] frame.
 *
 * Null when there is no outline, and null when it covers the whole frame: a frame-sized outline
 * adds nothing but a resample, which is the same rule [ScanProcessor.cropIfNeeded] applies. This
 * is also where an outline held in normalised coordinates is scaled back up to whatever frame the
 * caller happens to have decoded, so the same stored geometry can be rendered at the editor's
 * 2400-edge copy and at the full 2600-edge one.
 */
fun Quad?.inFrame(width: Int, height: Int): Quad? =
    this?.takeIf { it.area() < 0.999 }?.scaled(width.toDouble(), height.toDouble())