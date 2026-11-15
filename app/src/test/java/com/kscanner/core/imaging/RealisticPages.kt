package com.kscanner.core.imaging

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Fixtures shaped like what a phone camera actually delivers.
 *
 * [TestImages.asymmetricTextPage] draws every row of every line as a trapezoid whose width grows
 * linearly from 45% to 100% across the row. Its row profile therefore ramps monotonically, which is
 * the largest displacement a band can carry, and it is the only shape in the suite that carries the
 * flip cue at all. That makes it a fixture in which "upright implies positive bias" holds *by
 * construction*: it cannot produce the reading a real photograph produces.
 *
 * This object draws the other thing. Text is set from a 5x7 bitmap font, so the ink within a line
 * has whatever vertical distribution the letterforms happen to have — ascenders reach above the
 * x-height, descenders below the baseline, and the x-height band is where the mass sits. Nothing
 * here is tuned to a target value: [FONT] is a font, not a cue. On top of that a page carries the
 * ink a page carries — rules, a table, an underline, a stamp, a signature — and the photograph is
 * lit the way a desk is lit: a gradient, a shadow across part of the sheet, corner falloff.
 */
object RealisticPages {

    // ------------------------------------------------------------------ metrics

    /** Cell height of [FONT], in font pixels. */
    const val FONT_ROWS = 7

    /** Cell width of [FONT], in font pixels. */
    const val FONT_COLS = 5

    /**
     * A 5x7 bitmap font covering lower case, the capitals English prose starts sentences with, and
     * the punctuation a page of print contains. Rows run top to bottom; row 0 is the ascender/cap
     * top, rows 1-5 are the x-height band, row 6 is the descender band, and the baseline sits
     * between rows 5 and 6. Lower case letters use those extents honestly: `b d f h k l t` reach
     * row 0, `g j p q y` reach row 6, the rest stay inside rows 1-5. The font is schematic but its
     * vertical *extents* are the real ones, and those extents are what set the row profile.
     */
    private val FONT: Map<Char, String> = mapOf(
        'a' to ".....|.###.|....#|.####|#...#|.####|.....",
        'b' to "#....|#....|#.##.|##..#|#...#|#.##.|.....",
        'c' to ".....|.###.|#...#|#....|#...#|.###.|.....",
        'd' to "....#|....#|.####|#...#|#...#|.####|.....",
        'e' to ".....|.###.|#...#|#####|#....|.###.|.....",
        'f' to "..##.|.#...|####.|.#...|.#...|.#...|.....",
        'g' to ".....|.####|#...#|#...#|.####|....#|.###.",
        'h' to "#....|#....|#.##.|##..#|#...#|#...#|.....",
        'i' to "..#..|.....|.##..|..#..|..#..|.###.|.....",
        'j' to "...#.|.....|..##.|...#.|...#.|#..#.|.##..",
        'k' to "#....|#....|#..#.|#.#..|##...|#.#..|.....",
        'l' to ".##..|..#..|..#..|..#..|..#..|.###.|.....",
        'm' to ".....|##.#.|#.#.#|#.#.#|#...#|#...#|.....",
        'n' to ".....|#.##.|##..#|#...#|#...#|#...#|.....",
        'o' to ".....|.###.|#...#|#...#|#...#|.###.|.....",
        'p' to ".....|#.##.|##..#|#...#|#.##.|#....|#....",
        'q' to ".....|.####|#...#|#...#|.####|....#|....#",
        'r' to ".....|#.##.|##..#|#....|#....|#....|.....",
        's' to ".....|.####|#....|.###.|....#|####.|.....",
        't' to ".#...|.#...|####.|.#...|.#..#|..##.|.....",
        'u' to ".....|#...#|#...#|#...#|#...#|.####|.....",
        'v' to ".....|#...#|#...#|#...#|.#.#.|..#..|.....",
        'w' to ".....|#...#|#...#|#.#.#|#.#.#|.#.#.|.....",
        'x' to ".....|#...#|.#.#.|..#..|.#.#.|#...#|.....",
        'y' to ".....|#...#|#...#|#...#|.####|....#|.###.",
        'z' to ".....|#####|...#.|..#..|.#...|#####|.....",
        'A' to "..#..|.#.#.|#...#|#####|#...#|#...#|.....",
        'I' to "#####|..#..|..#..|..#..|..#..|#####|.....",
        'T' to "#####|..#..|..#..|..#..|..#..|..#..|.....",
        '.' to ".....|.....|.....|.....|.....|.##..|.....",
        ',' to ".....|.....|.....|.....|.##..|..#..|.#...",
        ' ' to ".....|.....|.....|.....|.....|.....|.....",
    )

    private val GLYPHS: Map<Char, List<BooleanArray>> = FONT.mapValues { (_, rows) ->
        rows.split('|').map { row -> BooleanArray(FONT_COLS) { row[it] == '#' } }
    }

    /** Page-space advance of one character, in pixels, at [scale] pixels per font pixel. */
    fun advance(scale: Int): Int = (FONT_COLS + 1) * scale

    /** Page-space height of one glyph cell, in pixels, at [scale]. */
    fun cellHeight(scale: Int): Int = FONT_ROWS * scale

    // ------------------------------------------------------------------ text

    /**
     * Draws [text] as one line of print with its cap top at [top] and its left edge at [left].
     * Returns the x just past the last character. Characters outside [FONT] are drawn as blanks.
     */
    fun drawText(page: GrayImage, text: String, left: Int, top: Int, scale: Int, ink: Int): Int {
        var x = left
        for (ch in text) {
            val glyph = GLYPHS[ch] ?: GLYPHS[' ']!!
            for (row in 0 until FONT_ROWS) {
                for (col in 0 until FONT_COLS) {
                    if (!glyph[row][col]) continue
                    for (dy in 0 until scale) {
                        for (dx in 0 until scale) {
                            val px = x + col * scale + dx
                            val py = top + row * scale + dy
                            if (px >= 0 && px < page.width && py >= 0 && py < page.height) {
                                page.set(px, py, ink)
                            }
                        }
                    }
                }
            }
            x += advance(scale)
        }
        return x
    }

    /** A sentence from [CORPUS] wrapped to [chars] per line, starting at [top]. */
    fun drawParagraph(
        page: GrayImage,
        chars: Int,
        lines: Int,
        left: Int,
        top: Int,
        scale: Int,
        ink: Int,
        linePitch: Int,
    ) {
        val words = CORPUS.split(' ')
        // The corpus is cycled rather than exhausted: a long page is a long page, and a generator
        // that stopped writing after one paragraph would silently turn every long fixture into the
        // same short one.
        var index = 0
        for (line in 0 until lines) {
            val builder = StringBuilder()
            while (true) {
                val word = words[index % words.size]
                val next = if (builder.isEmpty()) word else "$builder $word"
                if (next.length > chars && builder.isNotEmpty()) break
                builder.clear().append(if (next.length > chars) word else next)
                index++
                if (builder.length >= chars) break
            }
            drawText(page, builder.toString(), left, top + line * linePitch, scale, ink)
        }
    }

    /** Ordinary prose, used as the source of the rendered lines. */
    const val CORPUS: String =
        "The quick brown fox jumps over the lazy dog while twenty six letters of the alphabet " +
            "arrange themselves into words that a scanner must read and a human must recognise " +
            "without turning the page over in their hands or asking a machine to guess again."

    // ------------------------------------------------------------------ ink a page carries

    /** A horizontal rule across [page], [thickness] pixels tall. */
    fun rule(page: GrayImage, x0: Int, x1: Int, y: Int, thickness: Int, ink: Int) {
        for (yy in y until y + thickness) {
            for (xx in x0 until x1) {
                if (yy in 0 until page.height && xx in 0 until page.width) page.set(xx, yy, ink)
            }
        }
    }

    /** A vertical rule. */
    fun vrule(page: GrayImage, x: Int, y0: Int, y1: Int, thickness: Int, ink: Int) {
        for (xx in x until x + thickness) {
            for (yy in y0 until y1) {
                if (yy in 0 until page.height && xx in 0 until page.width) page.set(xx, yy, ink)
            }
        }
    }

    /**
     * A grid of [rows] by [columns] cells between (x0, y0) and (x1, y1), with the cell text drawn
     * from [CORPUS]. A real table: it has line-like ink along both axes.
     */
    fun table(
        page: GrayImage,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        rows: Int,
        columns: Int,
        thickness: Int,
        ink: Int,
        scale: Int,
    ) {
        val rowHeight = (y1 - y0) / rows
        val colWidth = (x1 - x0) / columns
        for (r in 0..rows) rule(page, x0, x1, y0 + r * rowHeight, thickness, ink)
        for (c in 0..columns) vrule(page, x0 + c * colWidth, y0, y1, thickness, ink)
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                drawText(
                    page,
                    "abc dex",
                    x0 + c * colWidth + 4 * scale,
                    y0 + r * rowHeight + 6,
                    scale,
                    ink,
                )
            }
        }
    }

    /** An underline under a line of print: ink strictly below the baseline. */
    fun underline(page: GrayImage, x0: Int, x1: Int, y: Int, thickness: Int, ink: Int) =
        rule(page, x0, x1, y, y + thickness, ink)

    /** A solid rectangular stamp or ink blot. */
    fun stamp(page: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int, ink: Int) {
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                if (y in 0 until page.height && x in 0 until page.width) page.set(x, y, ink)
            }
        }
    }

    /**
     * A signature: three parametric strokes that run diagonally and loop, the way a hand does.
     * Nothing about it is axis-aligned, which is the point — it is ink that is not a text row.
     */
    fun signature(page: GrayImage, x0: Int, y0: Int, width: Int, height: Int, thickness: Int, ink: Int) {
        for (step in 0 until width * 4) {
            val t = step / (width * 4.0)
            val x = x0 + (t * width).toInt()
            val y = y0 + (height * (0.55 + 0.45 * sin(t * 9.0))).toInt()
            stamp(page, x, y, x + thickness, y + thickness, ink)
        }
        for (step in 0 until width / 2) {
            val t = step / (width / 2.0)
            val x = x0 + width / 3 + (t * width / 3).toInt()
            val y = y0 + height - (t * height * 0.8).toInt()
            stamp(page, x, y, x + thickness, y + thickness, ink)
        }
        for (step in 0 until width / 3) {
            val t = step / (width / 3.0)
            val x = x0 + (t * width * 0.8).toInt()
            val y = y0 + height / 2 + (cos(t * 6.0) * height * 0.35).toInt()
            stamp(page, x, y, x + thickness, y + thickness, ink)
        }
    }

    // ------------------------------------------------------------------ the desk

    /**
     * Multiplies every sample by a linear ramp that runs from [topLeft] to [bottomRight] across the
     * frame. This is the uneven lighting of a lamp held to one side; the page itself is unchanged.
     */
    fun gradientLighting(page: GrayImage, topLeft: Double, bottomRight: Double): GrayImage {
        val out = IntArray(page.size)
        for (y in 0 until page.height) {
            for (x in 0 until page.width) {
                val t = (x.toDouble() / page.width + y.toDouble() / page.height) / 2.0
                val factor = topLeft + (bottomRight - topLeft) * t
                out[y * page.width + x] = Px.clamp255(page.get(x, y) * factor)
            }
        }
        return GrayImage(page.width, page.height, out)
    }

    /**
     * Darkens the right-hand [fraction] of the page with a soft edge, as a hand or a phone body
     * casts a shadow across part of the sheet.
     */
    fun shadowFromRight(page: GrayImage, fraction: Double, strength: Double, softness: Double): GrayImage {
        val out = IntArray(page.size)
        val edge = page.width * (1.0 - fraction)
        for (y in 0 until page.height) {
            for (x in 0 until page.width) {
                val d = (x - edge) / (page.width * softness)
                val shade = 1.0 - strength / (1.0 + exp(-d))
                out[y * page.width + x] = Px.clamp255(page.get(x, y) * shade)
            }
        }
        return GrayImage(page.width, page.height, out)
    }

    /** Darkens the bottom [fraction] of the page with a soft edge. */
    fun shadowFromBottom(page: GrayImage, fraction: Double, strength: Double, softness: Double): GrayImage {
        val out = IntArray(page.size)
        val edge = page.height * (1.0 - fraction)
        for (y in 0 until page.height) {
            for (x in 0 until page.width) {
                val d = (y - edge) / (page.height * softness)
                val shade = 1.0 - strength / (1.0 + exp(-d))
                out[y * page.width + x] = Px.clamp255(page.get(x, y) * shade)
            }
        }
        return GrayImage(page.width, page.height, out)
    }

    /** Corner falloff, the way a small lens vignettes a page. */
    fun vignette(page: GrayImage, strength: Double): GrayImage {
        val out = IntArray(page.size)
        val cx = page.width / 2.0
        val cy = page.height / 2.0
        val maxR = kotlin.math.sqrt(cx * cx + cy * cy)
        for (y in 0 until page.height) {
            for (x in 0 until page.width) {
                val dx = x - cx
                val dy = y - cy
                val r = kotlin.math.sqrt(dx * dx + dy * dy) / maxR
                out[y * page.width + x] = Px.clamp255(page.get(x, y) * (1.0 - strength * r * r))
            }
        }
        return GrayImage(page.width, page.height, out)
    }

    // ------------------------------------------------------------------ composition

    /** A blank page of paper. */
    fun page(width: Int = 900, height: Int = 1200, paper: Int = 0xF6): GrayImage =
        GrayImage.filled(width, height, paper)

    /**
     * Transforms.rotate180 for a [GrayImage], built from the same quarter turn [Orientation] uses,
     * so a fixture turned over here is turned over by the code under test's own arithmetic.
     */
    fun turnOver(page: GrayImage): GrayImage {
        var current = page
        repeat(2) { current = Orientation.rotate90(current) }
        return current
    }

    /**
     * Photographs [page] onto a dark surface through [quad], reproducing what the camera gives the
     * pipeline: the sheet at an angle, darker background around it, and bilinear resampling.
     */
    fun photograph(
        page: GrayImage,
        canvasWidth: Int,
        canvasHeight: Int,
        quad: Quad,
        background: Int = 0x30,
    ): RgbaImage {
        val image = RgbaImage.filled(canvasWidth, canvasHeight, Px.rgb(background, background, background))
        val pageRect = Quad(
            Pt(0.0, 0.0),
            Pt((page.width - 1).toDouble(), 0.0),
            Pt((page.width - 1).toDouble(), (page.height - 1).toDouble()),
            Pt(0.0, (page.height - 1).toDouble()),
        )
        val inverse = Homography.solve(quad.points, pageRect.points)
        for (y in 0 until canvasHeight) {
            for (x in 0 until canvasWidth) {
                val c = Pt(x + 0.5, y + 0.5)
                if (!quad.containsPoint(c)) continue
                val p = Homography.map(inverse, c)
                if (p.x.isNaN() || p.y.isNaN()) continue
                image.set(x, y, grayToRgba(page, p.x, p.y))
            }
        }
        return image
    }

    private fun grayToRgba(page: GrayImage, x: Double, y: Double): Int {
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun at(cx: Int, cy: Int): Double =
            page.get(cx.coerceIn(0, page.width - 1), cy.coerceIn(0, page.height - 1)).toDouble()
        val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * fx
        val bottom = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * fx
        val v = (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
        return Px.rgb(v, v, v)
    }

    // ------------------------------------------------------------------ named fixtures

    const val INK = 0x14

    /** A full page of ordinary prose, unlit: every one of the six cues' baseline case. */
    fun prosePage(
        width: Int = 900,
        height: Int = 1200,
        lines: Int = 20,
        scale: Int = 4,
        ink: Int = INK,
    ): GrayImage {
        val page = page(width, height)
        val pitch = cellHeight(scale) + 4 * scale
        val chars = ((width - 120) / advance(scale))
        drawParagraph(page, chars, lines, 60, 60, scale, ink, pitch)
        return page
    }

    /**
     * A page photographed in the least forgiving light: a gradient across the whole sheet, a soft
     * shadow over the right third, and corner falloff. No synthetic cue is added to compensate.
     */
    fun prosePageUnderUnevenLight(width: Int = 900, height: Int = 1200, lines: Int = 20): GrayImage {
        var page = prosePage(width, height, lines)
        page = gradientLighting(page, 1.10, 0.62)
        page = shadowFromRight(page, fraction = 0.34, strength = 0.40, softness = 0.06)
        page = vignette(page, 0.18)
        return page
    }

    /** A page with a hard shadow across its lower half, the way a forearm crosses a sheet. */
    fun prosePageShadowedLowHalf(width: Int = 900, height: Int = 1200, lines: Int = 20): GrayImage {
        var page = prosePage(width, height, lines)
        page = shadowFromBottom(page, fraction = 0.48, strength = 0.55, softness = 0.03)
        return page
    }

    /** Mostly blank, four lines of print in the middle: a letter, a title page, a receipt head. */
    fun sparseTextBlock(width: Int = 900, height: Int = 1200): GrayImage {
        val page = page(width, height)
        val scale = 4
        val pitch = cellHeight(scale) + 4 * scale
        drawParagraph(page, 24, 4, 200, 520, scale, INK, pitch)
        return page
    }

    /** A boxed table of text: line-like ink along both axes, and little to tell 0 from 180. */
    fun tablePage(width: Int = 900, height: Int = 1200): GrayImage {
        val page = page(width, height)
        table(page, 80, 180, width - 80, 1000, rows = 8, columns = 3, thickness = 3, ink = INK, scale = 3)
        return page
    }

    /** Prose with a horizontal rule above it and a rule below: non-glyph ink that is a text row. */
    fun proseWithRules(width: Int = 900, height: Int = 1200, lines: Int = 18): GrayImage {
        val page = prosePage(width, height, lines)
        rule(page, 60, width - 60, 40, 4, INK)
        rule(page, 60, width - 60, height - 60, 4, INK)
        return page
    }

    /** Prose with an underline under every third line: ink that sits below the baseline. */
    fun proseWithUnderlines(width: Int = 900, height: Int = 1200, lines: Int = 18): GrayImage {
        val page = page(width, height)
        val scale = 4
        val pitch = cellHeight(scale) + 4 * scale
        val chars = ((width - 120) / advance(scale))
        drawParagraph(page, chars, lines, 60, 60, scale, INK, pitch)
        for (line in 0 until lines step 3) {
            underline(page, 60, width - 60, 60 + line * pitch + cellHeight(scale) + scale, 3, INK)
        }
        return page
    }

    /** Prose with a solid stamp over one corner: a large, uniform, high-mass non-text band. */
    fun proseWithStamp(width: Int = 900, height: Int = 1200, lines: Int = 18): GrayImage {
        val page = prosePage(width, height, lines)
        stamp(page, 620, 900, 800, 1080, INK)
        return page
    }

    /** Prose with a signature under it: diagonal ink with no row structure at all. */
    fun proseWithSignature(width: Int = 900, height: Int = 1200, lines: Int = 16): GrayImage {
        val page = prosePage(width, height, lines)
        signature(page, 480, 960, 320, 120, thickness = 3, ink = INK)
        return page
    }

    /**
     * Two columns of equal-height print: the row profile has as many peaks as there are lines and
     * the column profile has two tall ones, so the block is near-square and neither axis clearly
     * wins. This is the layout that makes the {0,180} versus {90,270} cue a coin toss.
     */
    fun twoColumnPage(width: Int = 900, height: Int = 1200): GrayImage {
        val page = page(width, height)
        val scale = 4
        val pitch = cellHeight(scale) + 4 * scale
        val chars = ((width / 2 - 100) / advance(scale))
        drawParagraph(page, chars, 18, 60, 80, scale, INK, pitch)
        drawParagraph(page, chars, 18, width / 2 + 40, 80, scale, INK, pitch)
        return page
    }

    /** A page carrying only a large solid block: no text at all, but plenty of ink. */
    fun solidBlockPage(width: Int = 900, height: Int = 1200): GrayImage {
        val page = page(width, height)
        stamp(page, 250, 350, 650, 850, INK)
        return page
    }

    /**
     * A square block of prose: twelve short lines set in a 520 by 520 area. The block is as tall as
     * it is wide, so the line-stacking axis is no more prominent than the other one and the
     * {0,180}-versus-{90,270} cue has nothing to choose on.
     */
    fun squareTextBlock(width: Int = 900, height: Int = 1200): GrayImage {
        val page = page(width, height)
        val scale = 4
        val pitch = cellHeight(scale) + 4 * scale
        drawParagraph(page, 21, 12, 190, 340, scale, INK, pitch)
        return page
    }

    /**
     * Counts how many samples of [page] are at least [bias] darker than the mean of the whole
     * frame. Used to check that a fixture really carries the ink it claims to.
     */
    fun darkFraction(page: GrayImage, bias: Int = 40): Double {
        var total = 0.0
        var dark = 0
        for (v in page.data) total += v
        val mean = total / page.size
        for (v in page.data) if (v < mean - bias) dark++
        return dark.toDouble() / page.size
    }
}
