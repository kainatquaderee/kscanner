package com.kscanner.core.imaging

import kotlin.math.abs
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
 * it, level the flattened page, read the orientation from that levelled copy and the residual
 * text skew from the upright page, then report the rotation to apply to the whole capture with
 * the outline rotated to match.
 *
 * Kept framework-free so the exact preparation the app runs is the one the tests run.
 */
object AutoCrop {

    /**
     * Below this the page counts as level and is handed to the orientation cue untouched.
     * [Skew] resolves a few tenths of a degree, so a smaller reading is noise, and skipping
     * it keeps the rotation out of every ordinary capture.
     */
    private const val LEVEL_THRESHOLD_DEGREES = 0.15

    /** Trimmed from every side of the levelled page, covering the rotation's corner wedges. */
    private const val BORDER_TRIM_FRACTION = 0.02

    /**
     * Share of the frame the detector's first pass has to account for before its answer is taken as
     * it stands. Measured across our nineteen fixtures: six rows sit above it and every one of them
     * is correct to 0.001 of truth, thirteen sit below and ten of those are the fragments this
     * decision exists to catch; the nearest row below is 0.570, the nearest above 0.672.
     *
     * The margin on the low side is thin on purpose. This gate never decides *what* the crop is,
     * only whether the frame is examined again, and that examination is itself guarded by the rim
     * test and the ceiling. Set too low it costs the second look on a frame that did not need one;
     * set too high it leaves a fragment delivered, which is the one outcome a crop must never have.
     */
    private const val MIN_PAGE_COVERAGE = 0.60

    /**
     * Share of the frame's rim that has to read as paper before we conclude the sheet runs past the
     * view and deliver the frame whole.
     *
     * Measured: the two overhangs read 1.00 and 0.88 and the desk-as-bright frame 1.00, and all
     * three have no page-shaped crop to make; the highest non-fire is 0.40 — the tall sheet, which
     * overhangs only top and bottom, so the mean dilutes two bright sides with two dark ones — and
     * the nearest interior frame reads 0.22. That leaves 0.18 below and 0.30 above.
     *
     * We take the mean of the four sides rather than their minimum because a minimum answers "is
     * every side paper", which is true only of the 25 px overhang: the 80 px one reads 0.66 on its
     * top side, since the sheet's own text margin is narrower than the drawn page's. A count of
     * three of four agrees with the mean on all nineteen rows and is a step function over the same
     * four readings, so the mean is the honest form of the same test.
     */
    private const val SHEET_PAST_THE_VIEW = 0.70

    /**
     * Ceiling on what the second look may believe. Un-guarded, it returns 0.997 on a page covering
     * 0.115 of the frame, and 0.997 on the same fixture is the flat-field turning a lit desk into
     * paper and the detector finding the frame's own edge. Every genuine page we measured sits
     * below this and the frame's own edge above it.
     */
    private const val WHOLE_FRAME_CEILING = 0.98

    /**
     * Width of each rim band, in pixels, on the preview the decision runs on. Three pixels around a
     * 1600x2133 photograph is about 12k samples — enough for the share to be stable and short
     * enough that the band stays on the very edge, where a sheet that runs past the view leaves
     * paper and a sheet inside the frame leaves the surface behind it.
     */
    private const val RIM_BAND_PIXELS = 3

    /**
     * Grey levels below the frame's own paper level that a rim pixel may sit and still count as
     * paper. The fixtures separate paper from a shadowed desk at this offset without letting a
     * highlight on the desk's gloss into the paper level.
     */
    private const val RIM_TOLERANCE = 12

    /**
     * The brightest share of the frame the paper level is the median of. It has to be the paper's
     * own level rather than a divider of the frame, because on a light desk the desk is the brighter
     * of the two: an Otsu-anchored rim test reads 1.00 on the light-desk fixture, where the first
     * pass is already right, and would deliver the whole frame over a correct crop.
     */
    private const val PAPER_LEVEL_SHARE = 0.02

    /** Working edge of the second look. Bounded below the first pass because it is the repair. */
    private const val SECOND_LOOK_EDGE = 700

    /**
     * How much the second look has to grow the first pass's answer before it replaces it.
     *
     * The two passes resample differently — the first through a resized colour copy, the second
     * through the resize the gray overload does on the flat-fielded frame — so on a frame where the
     * page was already found they still disagree by a pixel or two. Across our nineteen fixtures
     * that disagreement never exceeds 0.0011 of the frame, and on the two rows that reach the second
     * look without it being a repair it is 0.0002. The repairs themselves grow the answer by 0.054
     * to 0.349. This floor sits in that gap: nine times above the largest disagreement we measured,
     * and five times below the smallest repair.
     *
     * It is not cosmetic, because everything downstream is derived from the chosen outline: the
     * flattened page the orientation cue reads, the deskew, the outline that is reported and the
     * crop itself. Replacing an answer for a gain inside the measurement's own noise re-samples the
     * page — and a reading taken on the new sampling can land on the far side of its own threshold.
     * Measured on the photographed upside-down page of prose under uneven light that the orientation
     * contract test covers: the two passes differ by 0.0005, and adopting on that turns a page the
     * cue had turned through 180 degrees into one it cannot decide at all.
     */
    private const val REPAIR_GAIN_COVERAGE = 0.01

    /** Shortest blur radius the flat-field uses; below this the local background is the paper. */
    private const val FLATTEN_MIN_RADIUS = 12

    /** Divisor of the frame's longest edge that sets the flat-field's blur radius. */
    private const val FLATTEN_RADIUS_DIVISOR = 9

    /** Floor on the flat-field's background estimate, so a near-black frame cannot divide by ~0. */
    private const val FLATTEN_FLOOR_LEVEL = 40

    data class Plan(
        /** Clockwise rotation that makes the capture upright, in quarter turns. */
        val rotationDegrees: Int,
        /**
         * True when the 0-versus-180 half of [rotationDegrees] was decided.
         *
         * False means the automatic pass found no evidence to choose between the page and
         * the same page turned over — too few text bands, too little ink, or a symmetric
         * layout — and left the page as captured. [rotationDegrees] is the answer either
         * way; this only tells a caller whether a one-tap flip is worth offering.
         */
        val flipDecided: Boolean,
        /**
         * Why [flipDecided] came out the way it did, for a caller that wants to say more than
         * "I could not tell". [Orientation.FlipReason.ONE_SIDE_ABSTAINED] when the orientation
         * cue never ran.
         */
        val flipReason: Orientation.FlipReason,
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
        val choice = if (detectOutline) decide(preview, aspectHint) else null
        val chosen = choice?.quad

        val page: GrayImage = if (chosen != null) {
            flatten(preview, chosen) ?: preview.toGray()
        } else {
            preview.toGray()
        }

        // Orientation is read from the levelled page, not the raw one: see [level].
        val orientation = if (fixOrientation) Orientation.estimate(level(page)) else Orientation.UPRIGHT
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
        val outline: Pair<Quad, Quad>? = chosen?.let { found ->
            val pixels = rotateQuad(found, preview.width, preview.height, rotation)
            val fractions = pixels.scaled(1.0 / rotatedPreviewWidth, 1.0 / rotatedPreviewHeight)
            if (insideFrame(fractions)) pixels to fractions else null
        }

        return Plan(
            rotationDegrees = rotation,
            flipDecided = orientation.flipDecided,
            flipReason = orientation.flipReason,
            quad = outline?.first?.scaled(
                rotatedImageWidth.toDouble() / rotatedPreviewWidth,
                rotatedImageHeight.toDouble() / rotatedPreviewHeight,
            ),
            quadNormalised = outline?.second,
            deskewDegrees = deskew,
            method = if (outline != null) choice?.method else null,
            confidence = if (outline != null) max(0.0, choice?.confidence ?: 0.0) else 0.0,
            quality = Quality.analyse(uprightPage, hasDocument = outline != null),
        )
    }

    /**
     * The outline the crop is made from, in pixels of the preview, with the detection it came from
     * — or nothing, which means the whole capture.
     */
    private data class Choice(val quad: Quad?, val method: DetectMethod?, val confidence: Double)

    /**
     * Which answer the crop is made from, in three steps.
     *
     * The detector answers with the largest clean paper-like rectangle it can score, and when the
     * lighting runs across the page, or the page runs off the frame, that rectangle is a *piece* of
     * the page: 0.327 for a sheet covering 0.998, 0.287 for the same sheet under a light gradient,
     * 0.228 where the desk is as bright as the paper. Every wrong answer we measured is too small —
     * not one of them keeps the desk — so the repair below can only grow an answer, never shrink
     * one, and leaving page content out stays impossible.
     *
     * 1. The first pass, as it always was. An answer covering at least [MIN_PAGE_COVERAGE] of the
     *    frame is taken and nothing else runs.
     * 2. The rim test: does the paper reach the frame's own edge? If it does, the sheet runs past
     *    the view and there is no page-shaped crop to make, so we answer with nothing and the whole
     *    capture is delivered. This runs *before* the second look, because a frame whose paper
     *    reaches its edges has no page-shaped answer to find whatever the flat-field produces.
     * 3. The second look: a flat-fielded copy of the frame, detected again. Its answer is adopted
     *    only when it grows the first one by more than [REPAIR_GAIN_COVERAGE] and stays below
     *    [WHOLE_FRAME_CEILING] — a repair, not a replacement. A first pass that found nothing has no
     *    answer to compare against, so it takes the second look only when that clears the same
     *    [MIN_PAGE_COVERAGE] gate, since a fragment is what we are guarding against either way.
     *
     * Both passes answer in the preview's own pixels, which is what makes them comparable: measuring
     * one frame's outline against another's would silently inflate it by the ratio of the two.
     */
    private fun decide(preview: RgbaImage, aspectHint: Double?): Choice {
        val first = DocDetector.detect(preview, aspectHint = aspectHint)
        val firstCoverage = coverageOf(first?.quad, preview.width, preview.height)
        if (first != null && firstCoverage >= MIN_PAGE_COVERAGE) {
            return Choice(first.quad, first.method, first.confidence)
        }

        val gray = preview.toGray()
        if (sheetPastTheView(gray)) return Choice(null, null, 0.0)

        // The flat-field makes paper and lit desk alike, which is what lets the second look find a
        // page edge the first pass lost to a gradient — and is also why it needs a ceiling.
        val flat = Threshold.normalizeIllumination(
            gray,
            radius = max(FLATTEN_MIN_RADIUS, max(gray.width, gray.height) / FLATTEN_RADIUS_DIVISOR),
            floorLevel = FLATTEN_FLOOR_LEVEL,
        )
        val second = DocDetector.detect(flat, workingEdge = SECOND_LOOK_EDGE)
        val secondCoverage = coverageOf(second?.quad, preview.width, preview.height)

        val adopt = if (first == null) {
            secondCoverage >= MIN_PAGE_COVERAGE
        } else {
            secondCoverage - firstCoverage > REPAIR_GAIN_COVERAGE &&
                secondCoverage < WHOLE_FRAME_CEILING
        }
        if (adopt && second != null) return Choice(second.quad, second.method, second.confidence)

        return if (first != null) {
            Choice(first.quad, first.method, first.confidence)
        } else {
            Choice(null, null, 0.0)
        }
    }

    /** Share of the frame an outline accounts for once it is clamped to the frame's bounds. */
    private fun coverageOf(quad: Quad?, width: Int, height: Int): Double {
        if (quad == null) return 0.0
        return quad.clampTo(width, height).area() / (width.toDouble() * height)
    }

    /**
     * Whether the paper reaches the frame's own edge, which means the sheet runs past the view.
     *
     * The test is a property of the *frame*, not of the detection: the frames that defeat the
     * detector are not the frames where it is unsure — it reports its fragment confidently — they
     * are the frames where the paper continues to the edge of what the camera saw.
     *
     * It is deliberately a *relative* test: the level is read from the frame's own brightest
     * [PAPER_LEVEL_SHARE], so the absolute exposure of the photograph drops out. That is what makes
     * it work on a dark desk and fail on a desk as bright as the paper, where the two are simply
     * the same level. The mean of the four sides is what fires: see [SHEET_PAST_THE_VIEW].
     */
    private fun sheetPastTheView(gray: GrayImage): Boolean {
        val threshold = paperLevel(gray) - RIM_TOLERANCE
        val band = minOf(RIM_BAND_PIXELS, gray.width, gray.height)

        val top = rowShare(gray, 0, band, threshold)
        val bottom = rowShare(gray, gray.height - band, gray.height, threshold)
        val left = columnShare(gray, 0, band, threshold)
        val right = columnShare(gray, gray.width - band, gray.width, threshold)

        return (top + bottom + left + right) / 4.0 >= SHEET_PAST_THE_VIEW
    }

    /**
     * The frame's paper level: the midpoint of the brightest [PAPER_LEVEL_SHARE] of its histogram.
     *
     * Walking down from white, [first] is the brightest level the frame uses at all — the few pixels
     * of a highlight or the top of the paper's own range — and [last] is where the cumulative count
     * reaches the share; the level we report is between them, so a single clipped highlight cannot
     * set the level on its own.
     */
    private fun paperLevel(gray: GrayImage): Int {
        val histogram = IntArray(256)
        for (v in gray.data) histogram[v.coerceIn(0, 255)]++

        val target = (gray.size * PAPER_LEVEL_SHARE).toInt().coerceAtLeast(1)
        var first = -1
        var last = -1
        var cumulative = 0
        for (level in 255 downTo 0) {
            val count = histogram[level]
            if (count == 0) continue
            if (first < 0) first = level
            cumulative += count
            if (last < 0 && cumulative >= target) last = level
        }
        return (first + last) / 2
    }

    /** Share of the rows in `[from, to)` whose pixels are at least [threshold]. */
    private fun rowShare(gray: GrayImage, from: Int, to: Int, threshold: Int): Double {
        var paper = 0
        var total = 0
        for (y in from until to) {
            val row = y * gray.width
            for (x in 0 until gray.width) {
                if (gray.data[row + x] >= threshold) paper++
                total++
            }
        }
        return paper.toDouble() / total
    }

    /** Share of the columns in `[from, to)` whose pixels are at least [threshold]. */
    private fun columnShare(gray: GrayImage, from: Int, to: Int, threshold: Int): Double {
        var paper = 0
        var total = 0
        for (y in 0 until gray.height) {
            val row = y * gray.width
            for (x in from until to) {
                if (gray.data[row + x] >= threshold) paper++
                total++
            }
        }
        return paper.toDouble() / total
    }

    /**
     * Removes the page's residual skew before the orientation is measured.
     *
     * The flip cue compares each text band's ink centroid with the band centre, and at the
     * working scale a band is a handful of pixels tall: one degree of tilt smears a row by
     * about its own height, so the displacement collapses and can even change sign. Measured
     * on a four-line page, 0.8 degrees of residual turned an upright page into an
     * upside-down one. A residual is the normal case, because the outline the page is
     * flattened with is a fit to the paper edge and only accurate to a few pixels, so the cue
     * is read from a levelled copy while the reported [Plan.deskewDegrees] stays what it
     * always was: the correction for the upright page, applied after cropping.
     *
     * A page that is already level skips the rotation entirely, which keeps the common case
     * byte-for-byte what it was.
     */
    private fun level(page: GrayImage): GrayImage {
        val skew = Skew.estimate(page).degrees
        if (abs(skew) < LEVEL_THRESHOLD_DEGREES) return page

        // The same resampling the deskew itself uses, which clamps to the frame edge: the
        // wedges a rotation exposes replicate the paper beside them. A black or white fill
        // would be ink in its own right and could enter a text band.
        val rotated = Transforms.rotateArbitrary(page, -skew)

        // Cropping the border keeps the replicated wedge and any page-edge residue out of the
        // profile, where they would otherwise form a band of their own. Two per cent is
        // enough to cover the wedge a few degrees of rotation exposes on a 720 px page.
        return cropBorder(rotated, BORDER_TRIM_FRACTION)
    }

    /** The interior of [gray], with [fraction] of it trimmed from every side. */
    private fun cropBorder(gray: GrayImage, fraction: Double): GrayImage {
        val dx = (gray.width * fraction).toInt()
        val dy = (gray.height * fraction).toInt()
        val width = gray.width - 2 * dx
        val height = gray.height - 2 * dy
        if (width < 8 || height < 8) return gray

        val out = IntArray(width * height)
        var i = 0
        for (y in dy until dy + height) {
            val row = y * gray.width + dx
            for (x in 0 until width) out[i++] = gray.data[row + x]
        }
        return GrayImage(width, height, out)
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
