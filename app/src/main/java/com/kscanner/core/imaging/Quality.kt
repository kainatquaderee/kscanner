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
        /** 0..1, higher is crisper. Below [SHARPNESS_BLURRY] the text will not be legible. */
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

    /** Longest edge used for the focus and glare measurements. */
    const val WORKING_EDGE = 640

    fun analyse(gray: GrayImage, hasDocument: Boolean = true): Report {
        val working = if (max(gray.width, gray.height) > WORKING_EDGE) {
            Transforms.resizeGray(gray, scaledWidth(gray.width, gray.height), scaledHeight(gray.width, gray.height))
        } else {
            gray
        }

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

    /** Maps the measurements onto the single thing the user should fix first. */
    fun advise(report: Report): CaptureAdvice = when {
        // A frame too dark to read is a lighting problem first; a featureless dark frame
        // would otherwise be misreported as "no document", which is not actionable.
        report.meanLuma < 62.0 -> CaptureAdvice.TOO_DARK
        !report.detectedText && report.inkFraction < 0.004 -> CaptureAdvice.NO_DOCUMENT
        report.sharpness < SHARPNESS_BLURRY -> CaptureAdvice.BLURRY
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
}
