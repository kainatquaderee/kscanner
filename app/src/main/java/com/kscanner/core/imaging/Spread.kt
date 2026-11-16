package com.kscanner.core.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Double-page spread handling for book scanning.
 *
 * A spread photographed from above is two pages joined at the gutter. Rather than asking
 * the user to crop each half, the whole spread is outlined once and then cut along the
 * gutter, so both pages come out of a single capture.
 *
 * The other half of the job is refusing: a photograph of one page has no gutter, and cutting
 * it in two produces pages that nothing in the app can put back together. [decide] is what
 * keeps that from happening.
 */
object Spread {

    /** The two halves of a spread, plus where the cut landed (a fraction of the width). */
    data class Split(val left: Quad, val right: Quad, val seam: Double)

    /**
     * Whether a flattened shot is a spread, and where its gutter runs.
     *
     * [seam] is a fraction of the width and is always inside the 0.30..0.70 that [findSeam]
     * searches, so it can be handed straight to [split] without a range check. On a shot
     * decided to be one page it still carries the placement [findSeam] found: a caller that
     * wants to say where the darkest column was does not have to run the search again.
     */
    data class Verdict(val isSpread: Boolean, val seam: Double)

    /**
     * Cuts [quad] into two pages along a roughly vertical seam.
     *
     * The seam runs between the top and bottom edges, so both halves keep the spread's
     * perspective: each page is still corrected by its own homography afterwards.
     */
    fun split(quad: Quad, seam: Double = 0.5): Split {
        val f = seam.coerceIn(0.2, 0.8)
        val top = Pt.lerp(quad.tl, quad.tr, f)
        val bottom = Pt.lerp(quad.bl, quad.br, f)
        return Split(
            left = Quad(quad.tl, top, bottom, quad.bl),
            right = Quad(top, quad.tr, quad.br, bottom),
            seam = f,
        )
    }

    /**
     * Locates the gutter of a flattened spread: the darkest vertical band near the middle.
     * Returns the seam as a fraction of the width, always within 0.30..0.70.
     */
    fun findSeam(warped: GrayImage): Double {
        val w = warped.width
        val h = warped.height
        if (w < 32 || h < 32) return 0.5

        val columns = columnMeans(warped)

        val lo = (w * 0.30).roundToInt().coerceAtLeast(1)
        val hi = (w * 0.70).roundToInt().coerceAtMost(w - 2)
        if (hi <= lo) return 0.5

        // A small horizontal average stops one noisy column from claiming the seam.
        val window = max(2, w / 60)
        var bestX = lo
        var bestScore = Double.MAX_VALUE
        for (x in lo..hi) {
            var acc = 0.0
            var n = 0
            for (d in -window..window) {
                val xi = x + d
                if (xi < 0 || xi >= w) continue
                acc += columns[xi]
                n++
            }
            val mean = acc / n
            // Pull the answer toward the centre: a dark page margin is not a gutter.
            val centrality = 1.0 - abs(x - w / 2.0) / (w / 2.0)
            val score = mean - 26.0 * centrality
            if (score < bestScore) {
                bestScore = score
                bestX = x
            }
        }
        return bestX.toDouble() / w
    }

    /**
     * Answers whether a flattened shot is a spread, and where its gutter runs.
     *
     * The evidence is the gutter itself, never a guess at the content: a **narrow dark run**
     * that **reaches the top and the bottom**, with **brighter paper immediately on both
     * sides of it** and a usable width of page either side. All four are measured on column
     * means and row means, which is why this runs in plain JVM tests with no framework.
     *
     * The search is [findSeam]'s, and the seam it returns is the seam this returns. What is
     * added here is the interrogation of that column:
     *
     * 1. **A narrow run.** Starting at the seam, the dark band is grown outward while the
     *    columns are still darker than the paper at the edges of the page. A crease is a few
     *    per cent of the width; a block of ink or a shadow lying across the middle of a page
     *    is tens of per cent, and [MAX_BAND_FRACTION] is what rejects it.
     * 2. **Paper on both sides, immediately outside the run, on each side separately.** Taking
     *    the two sides separately is what stops a shadow down one half of the sheet from
     *    passing as a gutter: it is dark next to paper on one side only. This is also where a
     *    column of print is caught — its neighbours are more print, not paper, so the depth
     *    collapses to a few levels.
     * 3. **The run reaches the top and the bottom.** Row by row, the band has to be darker
     *    than that row's *own* paper, so uneven lighting cannot manufacture a band. A column
     *    of print darkens the column *means* — that is exactly why the old single test on the
     *    centre column accepted a text column — but it leaves paper between the lines, so it
     *    fails here and only here when it is narrow enough to survive 1 and 2.
     *
     * Short of all three, the answer is one page. That default is deliberate: a spread left
     * whole is a page the user can see and deliberately re-shoot, while two half-pages cut out
     * of one page cannot be rejoined in the app at all.
     *
     * The thresholds are fitted, not invented ([ScanModesTest] carries the fixtures):
     *
     * * [MAX_BAND_FRACTION] — 0.20. The widest genuine crease in the fixtures is 6.3 % of the
     *   width (`TestImages.spreadWithGutter`, whose crease is 3 % either side of the seam);
     *   the narrowest dark run that is *not* a gutter is 44 %
     *   (`RealisticPages.solidBlockPage`, ink from a quarter to three quarters of the page).
     *   A fifth of the width sits in that gap, with room for the softer, wider shadow of a
     *   thick book's spine.
     * * [MIN_DEPTH] — 10 grey levels. Genuine gutters measure 96 to 166 levels below their
     *   surrounding paper in the fixtures; a page of print measures at most 5 beside its own
     *   text. The single fixture that beats 10 levels on both sides — a narrow column of
     *   solid-set print, 20 and 25 levels — is the case rule 3 exists for, and it has ink on
     *   only 55 % of its rows. 10 is below the 16 the replaced test used, because a gutter in
     *   bright light is shallow: `spreadWithGutter(crease = 0xFFE6E6E6)` is 12 levels deep and
     *   is a spread, where 16 would have called it one page.
     * * [MIN_DARK_ROW_FRACTION] — 0.90, at [MIN_ROW_DEPTH] of 8 levels. Every genuine gutter
     *   in the fixtures is dark at all 100 % of its rows, by construction: a crease runs the
     *   height of the sheet. The print column reaches 0.55 at best. A tenth of the rows may be
     *   lost to a finger, a shadow or a crease that fades without the shot stopping being a
     *   spread.
     * * [BAND_MARGIN] — 6 levels, the least a column must be below the page's paper to count
     *   as part of the run. It is comfortably under [MIN_DEPTH] so that even a shallow gutter
     *   leaves strips of real paper outside its own soft edge, rather than reading that edge
     *   as paper and then failing its own depth test.
     * * [PAD_FRACTION] — 0.02 of the width each side of the run: wide enough to average away a
     *   single noisy column, narrow enough to stay on the paper beside a crease that is 3 %
     *   wide either side.
     */
    fun decide(warped: GrayImage): Verdict {
        val w = warped.width
        val h = warped.height
        if (w < MIN_WIDTH || h < MIN_WIDTH) return Verdict(false, 0.5)

        val columns = columnMeans(warped)
        val seam = findSeam(warped)
        val seamX = (seam * w).roundToInt().coerceIn(0, w - 1)

        // The paper at the two outer edges, the dimmer of the two: the darker side sets the level
        // the band has to beat, so one underlit page cannot pass as a gutter of its own.
        val leftEdge = meanOf(columns, (w * 0.05).toInt(), (w * 0.20).toInt()) ?: return Verdict(false, seam)
        val rightEdge = meanOf(columns, (w * 0.80).toInt(), (w * 0.95).toInt()) ?: return Verdict(false, seam)
        val paper = min(leftEdge, rightEdge)
        val floor = paper - BAND_MARGIN

        var lo = seamX
        while (lo - 1 >= 0 && columns[lo - 1] < floor) lo--
        var hi = seamX
        while (hi + 1 < w && columns[hi + 1] < floor) hi++

        // 1. Narrow: a crease, not a quarter of the page.
        if ((hi - lo + 1).toDouble() / w > MAX_BAND_FRACTION) return Verdict(false, seam)

        // 2. Brighter paper on both sides of the run.
        val band = meanOf(columns, lo, hi) ?: return Verdict(false, seam)
        val pad = max(2, (w * PAD_FRACTION).toInt())
        val leftDepth = (meanOf(columns, lo - pad, lo - 1) ?: return Verdict(false, seam)) - band
        val rightDepth = (meanOf(columns, hi + 1, hi + pad) ?: return Verdict(false, seam)) - band
        if (leftDepth < MIN_DEPTH || rightDepth < MIN_DEPTH) return Verdict(false, seam)

        // 3. The run reaches the top and the bottom.
        if (darkRowFraction(warped, lo, hi) < MIN_DARK_ROW_FRACTION) return Verdict(false, seam)

        return Verdict(true, seam)
    }

    /** Mean grey level of every column, top to bottom: the profile a gutter shows up in. */
    private fun columnMeans(image: GrayImage): DoubleArray {
        val columns = DoubleArray(image.width)
        for (x in 0 until image.width) {
            var sum = 0L
            for (y in 0 until image.height) sum += image.get(x, y)
            columns[x] = sum.toDouble() / image.height
        }
        return columns
    }

    /** Mean of [columns] over `from..to`, or null when that range holds no column at all. */
    private fun meanOf(columns: DoubleArray, from: Int, to: Int): Double? {
        val a = max(from, 0)
        val b = min(to, columns.size - 1)
        if (b < a) return null
        var sum = 0.0
        for (x in a..b) sum += columns[x]
        return sum / (b - a + 1)
    }

    /**
     * The fraction of rows in which the band `from..to` is darker than that row's own paper.
     *
     * Row-local paper, not the frame's, so a gradient across the page or a shadow over its
     * lower half does not read as a band that stops halfway.
     */
    private fun darkRowFraction(image: GrayImage, from: Int, to: Int): Double {
        val w = image.width
        val leftFrom = (w * 0.05).toInt()
        val leftTo = (w * 0.20).toInt()
        val rightFrom = (w * 0.80).toInt()
        val rightTo = (w * 0.95).toInt()
        var dark = 0
        for (y in 0 until image.height) {
            var sideSum = 0.0
            var sideCount = 0
            for (x in leftFrom..leftTo) {
                sideSum += image.get(x, y)
                sideCount++
            }
            for (x in rightFrom..rightTo) {
                sideSum += image.get(x, y)
                sideCount++
            }
            var bandSum = 0.0
            for (x in from..to) bandSum += image.get(x, y)
            val rowPaper = sideSum / sideCount
            val rowBand = bandSum / (to - from + 1)
            if (rowPaper - rowBand >= MIN_ROW_DEPTH) dark++
        }
        return dark.toDouble() / image.height
    }

    /** Below this the measurements above have no room: two paper strips need a few columns each. */
    private const val MIN_WIDTH = 32

    /** Widest dark run that can still be a crease rather than a quarter of the page. */
    private const val MAX_BAND_FRACTION = 0.20

    /** Least amount, in grey levels, that the paper beside the band must be brighter than it. */
    private const val MIN_DEPTH = 10.0

    /** Least amount a column has to be below the paper to count as part of the dark run. */
    private const val BAND_MARGIN = 6.0

    /** Width of the strip of paper read on each side of the run, as a fraction of the image. */
    private const val PAD_FRACTION = 0.02

    /** Row-level depth for the full-height test; a little under [MIN_DEPTH], row means are noisier. */
    private const val MIN_ROW_DEPTH = 8.0

    /** Fraction of rows in which the band has to be dark for it to run the full height. */
    private const val MIN_DARK_ROW_FRACTION = 0.90
}
