package com.kscanner.core.imaging

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Builds one page out of two captures.
 *
 * The motivating case is an ID card: the front and back are separate photographs, but the
 * useful document is a single sheet showing both, the way a photocopier would produce it.
 * The same primitive lays out a receipt above its attachment or two related pages.
 */
object Composite {

    const val DEFAULT_GAP = 0.04
    const val DEFAULT_MARGIN = 0.05

    /** Stacks [top] over [bottom] at a common width on a white sheet. */
    fun stacked(
        top: RgbaImage,
        bottom: RgbaImage,
        gapFraction: Double = DEFAULT_GAP,
        marginFraction: Double = DEFAULT_MARGIN,
    ): RgbaImage {
        val width = max(top.width, bottom.width)
        val reference = max(width, max(top.height, bottom.height))
        val gap = (reference * gapFraction).roundToInt().coerceAtLeast(8)
        val margin = (reference * marginFraction).roundToInt().coerceAtLeast(10)

        val topPage = fitWidth(top, width)
        val bottomPage = fitWidth(bottom, width)
        val height = margin + topPage.height + gap + bottomPage.height + margin
        val out = RgbaImage.filled(width, height, Px.rgb(255, 255, 255))

        blit(out, topPage, 0, margin)
        blit(out, bottomPage, 0, margin + topPage.height + gap)
        return out
    }

    /** Scales [src] to exactly [width] pixels wide, keeping its aspect ratio. */
    private fun fitWidth(src: RgbaImage, width: Int): RgbaImage {
        if (src.width == width) return src
        val height = max(1, (src.height.toDouble() * width / src.width).roundToInt())
        return Transforms.resize(src, width, height)
    }

    private fun blit(dst: RgbaImage, src: RgbaImage, dx: Int, dy: Int) {
        for (y in 0 until src.height) {
            val ty = dy + y
            if (ty < 0 || ty >= dst.height) continue
            val srcRow = y * src.width
            val dstRow = ty * dst.width
            for (x in 0 until src.width) {
                val tx = dx + x
                if (tx < 0 || tx >= dst.width) continue
                dst.pixels[dstRow + tx] = src.pixels[srcRow + x]
            }
        }
    }
}
