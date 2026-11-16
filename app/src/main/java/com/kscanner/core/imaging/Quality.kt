package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the camera should tell the user about the frame it is looking at. */
enum class CaptureAdvice(val message: String) {
    GOOD("Ready"),
    NO_DOCUMENT("Point at a document"),
    BLURRY("Hold still"),
    TOO_DARK("Not enough light"),
    TOO_BRIGHT("Too bright"),
    GLARE("Glare on the page"),
    SHADOW("Even out the light"),
}

/**
 * Frame quality measurements.
 *
 * These exist for two reasons: the live overlay can tell someone *why* a shot is bad
 * instead of silently producing a mud-coloured scan, and the detector can be told when not
 * to trust its own gradients. Every measure is scale-aware: sharpness is computed at a
 * fixed working resolution so a 12 MP capture and a preview frame are comparable.
 */
object Quality {

    data class Report(
        /**
         * 0..1, higher is crisper. Below [SHARPNESS_BLURRY] the text will not be legible; a page
         * region is judged against [PAGE_SHARPNESS_BLURRY] instead.
         */
        val sharpness: Double,
        val meanLuma: Double,
        val contrast: Double,
        /** Share of pixels crushed to near-black. */
        val darkFraction: Double,
        /** Share of pixels blown out to near-white. */
        val brightFraction: Double,
        /** Share of pixels in large specular blobs, not counting the paper itself. */
        val glareFraction: Double,
        /** Difference between the brightest and darkest corner, normalised. */
        val illuminationRamp: Double,
        /** Share of pixels that look like ink. */
        val inkFraction: Double,
        val advice: CaptureAdvice,
        val detectedText: Boolean,
    ) {
        /** True when the frame is lit unevenly enough that flat-fielding will pay off. */
        val shadowHeavy: Boolean get() = illuminationRamp > 0.25 || darkFraction > 0.20
    }

    const val SHARPNESS_BLURRY = 0.055
    const val SHARPNESS_GOOD = 0.10

    /**
     * The [SHARPNESS_BLURRY] of the page region, fitted where [analysePage] actually runs.
     *
     * Sharpness here is a Laplacian variance, so it is a property of the *sampling rate* as much as
     * of the paper, and it goes the other way from what "more pixels means more detail" suggests:
     * measured on this build, the same page of prose filling the frame reads 0.0179 at 320 px,
     * 0.0117 at 400 px and 0.0085 at 465 px. What the measure sees is the pipeline's bilinear
     * passes, and a page drawn over more samples comes out with neighbours closer together. A frame
     * larger than [WORKING_EDGE] is resampled a second time and reads lower again — a 640 px shot
     * of that page reads 0.0056.
     *
     * [SHARPNESS_BLURRY] was fitted for a whole frame at [WORKING_EDGE] and is far above anything
     * the live analyser's own 320 px frame reaches
     * ([com.kscanner.ui.camera.AutoCaptureAnalyzer]'s `workingEdge`); judging a page region by it is
     * how a held page came to read BLURRY for ever and the automatic shutter never to fire. Measured
     * at 320 px, a page region reads 0.0177 for a full page of prose, 0.0165 with rules, 0.0118 for
     * two columns, 0.0075 for a boxed table and 0.0023 for a mostly blank sheet with four lines on
     * it, while the same pages blurred by a single box radius — the least blur there is — read 0.0007
     * or less. The line sits between the two: below it is a page that will not be legible, above it
     * is every page the fixtures can draw in focus.
     */
    const val PAGE_SHARPNESS_BLURRY = 0.0012

    /** Longest edge used for the focus and glare measurements. */
    const val WORKING_EDGE = 640

    /**
     * How far the page outline is pulled in before the page region is sampled, matching the 5 %
     * inset [AutoCrop.flatten] applies: the detected edge sits on the paper's boundary, and the
     * surface behind it would otherwise read as a row of ink along the rim.
     */
    private const val PAGE_INSET = 0.05

    /**
     * How far past a page's edge a pixel centre has to sit before the pixel is called outside.
     * Zero would put a pixel whose centre lands exactly on the line on the wrong side of it
     * depending on rounding, which costs a one-pixel rim of the page along every side; a hair of
     * slack leaves the boundary with the page, the same way [Quad.containsPoint] does.
     */
    private const val EDGE_TOLERANCE = 1e-6

    fun analyse(gray: GrayImage, hasDocument: Boolean = true): Report {
        val working = workingImage(gray)

        val mean = meanLuma(working)
        val variance = laplacianVariance(working)
        // Normalising by the squared mean keeps the score comparable between a dim room and
        // a bright one: the same page must not read as "blurry" just because it is dim.
        val scale = max(1.0, mean)
        val sharpness = variance / (scale * scale) / 40.0

        var dark = 0
        var bright = 0
        for (v in working.data) {
            if (v <= 26) dark++
            if (v >= 242) bright++
        }
        val n = working.size.toDouble()
        val darkFraction = dark / n
        val brightFraction = bright / n

        val ink = Segmentation.inkMask(working, radius = max(6, min(working.width, working.height) / 20))
        var inkCount = 0
        for (i in 0 until ink.size) if (ink.data[i] >= 128) inkCount++
        val inkFraction = inkCount / n

        val ramp = illuminationRamp(working)
        val glare = glareFraction(working, brightFraction)

        val report = Report(
            sharpness = sharpness.coerceIn(0.0, 1.0),
            meanLuma = mean,
            contrast = contrast(working),
            darkFraction = darkFraction,
            brightFraction = brightFraction,
            glareFraction = glare,
            illuminationRamp = ramp,
            inkFraction = inkFraction,
            advice = CaptureAdvice.GOOD,
            detectedText = hasDocument && inkFraction > 0.004 && inkFraction < 0.75,
        )
        return report.copy(advice = advise(report))
    }

    /**
     * The quality of the page itself: the same figures as [analyse], measured over the pixels
     * inside [outline] instead of over the whole frame.
     *
     * This is the measurement the automatic shutter keys on. A form over a large desk, a page
     * perfectly lit on a dark table and a page beside a bright window all read badly as *frames*
     * while the paper in the middle of them is fine, and it is the paper the user is pointing at.
     * [outline] is in [gray]'s own pixels — the coordinates [DocDetector] answers in, per
     * [Luma.sampleFrame] — so nothing here needs a view mapping.
     *
     * The outline is insetted by [PAGE_INSET] before sampling, the same "pull the edge in" the
     * flatten does ([AutoCrop.flatten] insets 5 %): the detected edge sits on the paper's
     * boundary, and the surface behind it would otherwise read as a row of ink along the rim.
     * Sharpness counts only pixels whose whole 4-neighbourhood is inside, so that same boundary
     * is not read as detail; the illumination ramp is the spread between the page's own bounding
     * box quadrants rather than the frame's; ink and glare keep their meanings, against the page
     * as the denominator.
     *
     * The thresholds [advise] carries were fitted to whole frames, so two of them are re-fitted
     * here and the numbers recorded. Everything below is measured on this build over 320x440
     * photographs — the live analyser's own working edge, where the page fills most of the frame
     * and the desk is `0x18` unless stated.
     *
     *  * **Sharpness.** In focus, the page region reads 0.0177 for a page of prose (the frame:
     *    0.0212), 0.0165 with rules, 0.0118 for two columns, 0.0075 for a boxed table and 0.0023
     *    for a mostly blank sheet with four lines on it; blurred by the smallest blur there is, a
     *    single box radius, it reads 0.0007 or less. [PAGE_SHARPNESS_BLURRY] is the line between
     *    them. The frame's own [SHARPNESS_BLURRY] is not: no page in focus comes near it at a live
     *    frame's scale, which is how a held page came to read BLURRY for ever and the automatic
     *    shutter never to fire.
     *  * **Illumination.** A page beside a bright window over a dark desk — the frame's quadrants
     *    spread 0.840, well over the SHADOW line — reads 0.063 here, because the spread is the
     *    light falling across the paper. A hard shadow across the page's lower half still reads
     *    0.495 and still says SHADOW, and a lamp held to one side still reads under the line at
     *    0.394. Neither [CaptureAdvice.SHADOW]'s 0.42 nor the other lines needed changing.
     *
     * What is left is the measurement itself. A page filling the frame over a dark desk reads
     * meanLuma 223.6 against the frame's 166.4, inkFraction 0.117 against 0.213 and ramp 0.063
     * against 0.049 — GOOD here where the frame is BLURRY. A 140x200 page in the middle of a
     * near-black desk reads meanLuma 223.7, GOOD, where the frame reads 54 to 61 and TOO_DARK. A
     * sparse page reads inkFraction 0.018 against the frame's 0.150, which is over the 0.004 that
     * [Report.detectedText] needs and so is not NO_DOCUMENT. A blank sheet still reads ink 0.000
     * and still is.
     */
    fun analysePage(gray: GrayImage, outline: Quad): Report {
        val working = workingImage(gray)
        val quad = outline
            .scaled(working.width.toDouble() / gray.width, working.height.toDouble() / gray.height)
            .insetBy(PAGE_INSET)
        val mask = maskOf(working, quad)

        var count = 0
        var left = working.width
        var top = working.height
        var right = -1
        var bottom = -1
        for (i in mask.indices) {
            if (!mask[i]) continue
            count++
            val x = i % working.width
            val y = i / working.width
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        // An outline with nothing inside it — one wholly off the frame — describes no page; the
        // frame's own reading is then the only true thing left to say.
        if (count == 0) return analyse(gray)

        val n = count.toDouble()
        var sum = 0L
        for (i in mask.indices) if (mask[i]) sum += working.data[i]
        val mean = sum / n

        var acc = 0.0
        var dark = 0
        var bright = 0
        for (i in mask.indices) {
            if (!mask[i]) continue
            val v = working.data[i]
            val d = v - mean
            acc += d * d
            if (v <= 26) dark++
            if (v >= 242) bright++
        }
        val brightFraction = bright / n

        // The page's own mean, not the frame's: the page is what is being judged, and a desk in
        // shadow must not make a bright page read as dim.
        val scale = max(1.0, mean)
        val variance = maskedLaplacianVariance(working, mask)
        val sharpness = (variance / (scale * scale) / 40.0).coerceIn(0.0, 1.0)

        val ink = Segmentation.inkMask(working, radius = max(6, min(working.width, working.height) / 20))
        var inkCount = 0
        for (i in mask.indices) if (mask[i] && ink.data[i] >= 128) inkCount++
        val inkFraction = inkCount / n

        val report = Report(
            sharpness = sharpness,
            meanLuma = mean,
            contrast = sqrt(acc / n),
            darkFraction = dark / n,
            brightFraction = brightFraction,
            glareFraction = maskedGlareFraction(working, brightFraction, mask, count),
            illuminationRamp = maskedIlluminationRamp(working, mask, left, top, right, bottom),
            inkFraction = inkFraction,
            advice = CaptureAdvice.GOOD,
            detectedText = inkFraction > 0.004 && inkFraction < 0.75,
        )
        return report.copy(advice = advise(report, PAGE_SHARPNESS_BLURRY))
    }

    /**
     * Maps the measurements onto the single thing the user should fix first.
     *
     * @param sharpnessFloor the blur line to judge [Report.sharpness] against. Sharpness is
     *   measured at a fixed working resolution but not at a fixed *scale*, so the line that is
     *   right for a [WORKING_EDGE] capture is not the line that is right for the 320 px frame the
     *   live analyser samples: the default is fitted for the former, and [analysePage] passes
     *   [PAGE_SHARPNESS_BLURRY] for the latter.
     */
    fun advise(report: Report, sharpnessFloor: Double = SHARPNESS_BLURRY): CaptureAdvice = when {
        // A frame too dark to read is a lighting problem first; a featureless dark frame
        // would otherwise be misreported as "no document", which is not actionable.
        report.meanLuma < 62.0 -> CaptureAdvice.TOO_DARK
        !report.detectedText && report.inkFraction < 0.004 -> CaptureAdvice.NO_DOCUMENT
        report.sharpness < sharpnessFloor -> CaptureAdvice.BLURRY
        report.glareFraction > 0.035 -> CaptureAdvice.GLARE
        report.illuminationRamp > 0.42 -> CaptureAdvice.SHADOW
        report.meanLuma > 232.0 && report.contrast < 18.0 -> CaptureAdvice.TOO_BRIGHT
        else -> CaptureAdvice.GOOD
    }

    fun meanLuma(gray: GrayImage): Double {
        var sum = 0L
        for (v in gray.data) sum += v
        return sum.toDouble() / gray.size
    }

    fun contrast(gray: GrayImage): Double {
        val mean = meanLuma(gray)
        var acc = 0.0
        for (v in gray.data) {
            val d = v - mean
            acc += d * d
        }
        return sqrt(acc / gray.size)
    }

    /** Variance of the 4-neighbour Laplacian. The classic focus measure. */
    fun laplacianVariance(gray: GrayImage): Double {
        val w = gray.width
        val h = gray.height
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val c = gray.data[row + x]
                val lap = (gray.data[row + x - 1] + gray.data[row + x + 1] +
                    gray.data[row - w + x] + gray.data[row + w + x] - 4 * c).toDouble()
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return max(0.0, sumSq / count - mean * mean)
    }

    /**
     * Share of the frame covered by large, solid bright blobs. A specular highlight is a
     * compact saturating patch, which separates it from ordinary white paper by requiring
     * the blob to be a small part of the frame and to be solid enough to be a highlight
     * rather than a bright background.
     */
    fun glareFraction(gray: GrayImage, brightFraction: Double): Double {
        // If almost everything is blown out the problem is exposure, not glare.
        if (brightFraction > 0.45) return 0.0
        val mask = Segmentation.brightMask(gray, 246)
        val labeled = Segmentation.label(mask, minArea = max(12, gray.size / 400))
        var worst = 0.0
        for (segment in labeled.segments) {
            if (segment.solidity < 0.45) continue
            val share = segment.area.toDouble() / gray.size
            if (share > 0.10) continue
            if (share > worst) worst = share
        }
        return worst
    }

    /**
     * Illumination ramp: the spread between quadrant means. A page lit from one side reads
     * high here, and the filters know to flatten it harder.
     */
    fun illuminationRamp(gray: GrayImage): Double {
        val halfW = gray.width / 2
        val halfH = gray.height / 2
        if (halfW < 2 || halfH < 2) return 0.0
        val sums = DoubleArray(4)
        val counts = IntArray(4)
        for (y in 0 until gray.height) {
            val quadY = if (y < halfH) 0 else 1
            val row = y * gray.width
            for (x in 0 until gray.width) {
                val q = quadY * 2 + (if (x < halfW) 0 else 1)
                sums[q] += gray.data[row + x]
                counts[q]++
            }
        }
        var min = Double.MAX_VALUE
        var max = 0.0
        for (i in 0..3) {
            if (counts[i] == 0) continue
            val m = sums[i] / counts[i]
            if (m < min) min = m
            if (m > max) max = m
        }
        if (max <= 0.0) return 0.0
        return ((max - min) / max).coerceIn(0.0, 1.0)
    }

    /** Estimate of remaining motion blur from two frames; 1.0 means "no change". */
    fun frameSimilarity(a: GrayImage, b: GrayImage): Double {
        if (a.width != b.width || a.height != b.height) return 0.0
        var diff = 0L
        var total = 0L
        for (i in a.data.indices step 4) {
            diff += abs(a.data[i] - b.data[i]).toLong()
            total += 255L
        }
        if (total == 0L) return 1.0
        return (1.0 - diff.toDouble() / total).coerceIn(0.0, 1.0)
    }

    private fun scaledWidth(width: Int, height: Int): Int {
        val longest = max(width, height)
        val scale = WORKING_EDGE.toDouble() / longest
        return max(1, (width * scale).roundToInt())
    }

    private fun scaledHeight(width: Int, height: Int): Int {
        val longest = max(width, height)
        val scale = WORKING_EDGE.toDouble() / longest
        return max(1, (height * scale).roundToInt())
    }

    /**
     * The image every measurement is taken on: the frame itself when it is small enough, and a
     * resize of it down to [WORKING_EDGE] otherwise. A 12 MP capture and a preview frame then
     * answer on the same scale, which is what makes [SHARPNESS_BLURRY] one number rather than a
     * table.
     */
    private fun workingImage(gray: GrayImage): GrayImage {
        if (max(gray.width, gray.height) <= WORKING_EDGE) return gray
        return Transforms.resizeGray(
            gray,
            scaledWidth(gray.width, gray.height),
            scaledHeight(gray.width, gray.height),
        )
    }

    /**
     * Which pixels of [gray] have their centre inside [quad], as a flat `y * width + x` mask.
     *
     * This is [Quad.containsPoint]'s test done once for a whole image instead of once per query.
     * A self-intersecting outline encloses no well-defined region, so nothing is masked and
     * [analysePage] falls back to the frame's own reading.
     */
    private fun maskOf(gray: GrayImage, quad: Quad): BooleanArray {
        val mask = BooleanArray(gray.size)
        if (!quad.isConvex()) return mask
        val p = quad.points
        var i = 0
        for (y in 0 until gray.height) {
            val py = y + 0.5
            for (x in 0 until gray.width) {
                val px = x + 0.5
                var positive = false
                var negative = false
                for (e in 0 until 4) {
                    val a = p[e]
                    val b = p[(e + 1) % 4]
                    val cross = (b.x - a.x) * (py - a.y) - (b.y - a.y) * (px - a.x)
                    if (cross > EDGE_TOLERANCE) positive = true
                    if (cross < -EDGE_TOLERANCE) negative = true
                }
                if (!(positive && negative)) mask[i] = true
                i++
            }
        }
        return mask
    }

    /**
     * [laplacianVariance] over the masked pixels whose whole 4-neighbourhood is masked too.
     *
     * The neighbourhood condition is the point of it: the page's boundary is the strongest edge
     * in the frame, and a Laplacian taken across it reads a crisp line as sharpness. Dropping the
     * rim leaves the paper's own texture — or its absence, on a page held still and out of focus.
     */
    private fun maskedLaplacianVariance(gray: GrayImage, mask: BooleanArray): Double {
        val w = gray.width
        val h = gray.height
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (!mask[i]) continue
                if (!mask[i - 1] || !mask[i + 1] || !mask[i - w] || !mask[i + w]) continue
                val c = gray.data[i]
                val lap = (gray.data[i - 1] + gray.data[i + 1] +
                    gray.data[i - w] + gray.data[i + w] - 4 * c).toDouble()
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return max(0.0, sumSq / count - mean * mean)
    }

    /**
     * [glareFraction] with the page as the denominator and the page as the place it has to be.
     *
     * @param count how many pixels the page covers
     */
    private fun maskedGlareFraction(
        gray: GrayImage,
        brightFraction: Double,
        mask: BooleanArray,
        count: Int,
    ): Double {
        if (brightFraction > 0.45) return 0.0
        if (count == 0) return 0.0
        val bright = Segmentation.brightMask(gray, 246)
        val labeled = Segmentation.label(bright, minArea = max(12, gray.size / 400))
        var worst = 0.0
        for (segment in labeled.segments) {
            if (segment.solidity < 0.45) continue
            // A highlight counts only where it falls on the paper. The same blob on the desk
            // beside the page is the desk's problem, and would otherwise raise a glare warning
            // about a page that has none on it.
            val cx = segment.centerX.toInt().coerceIn(0, gray.width - 1)
            val cy = segment.centerY.toInt().coerceIn(0, gray.height - 1)
            if (!mask[cy * gray.width + cx]) continue
            val share = segment.area.toDouble() / count
            if (share > 0.10) continue
            if (share > worst) worst = share
        }
        return worst
    }

    /**
     * [illuminationRamp] over the page's own bounding box, counting only the page's pixels.
     *
     * Splitting the frame instead would put the desk in one quadrant and the paper in another, so
     * a well-lit page on a dark table would read as a badly lit page. The box is the page's, so
     * the spread is the light falling across the paper.
     */
    private fun maskedIlluminationRamp(
        gray: GrayImage,
        mask: BooleanArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Double {
        if (right - left < 2 || bottom - top < 2) return 0.0
        val midX = (left + right) / 2
        val midY = (top + bottom) / 2
        val sums = DoubleArray(4)
        val counts = IntArray(4)
        for (y in top..bottom) {
            val quadY = if (y <= midY) 0 else 1
            val row = y * gray.width
            for (x in left..right) {
                val i = row + x
                if (!mask[i]) continue
                val q = quadY * 2 + (if (x <= midX) 0 else 1)
                sums[q] += gray.data[i]
                counts[q]++
            }
        }
        var quadrants = 0
        var min = Double.MAX_VALUE
        var max = 0.0
        for (q in 0..3) {
            if (counts[q] == 0) continue
            quadrants++
            val m = sums[q] / counts[q]
            if (m < min) min = m
            if (m > max) max = m
        }
        if (quadrants < 2 || max <= 0.0) return 0.0
        return ((max - min) / max).coerceIn(0.0, 1.0)
    }
}
