package com.kscanner.core.imaging

/** Synthetic images used by the imaging tests. */
object TestImages {

    const val PAGE_WIDTH = 300.0
    const val PAGE_HEIGHT = 420.0

    val PAGE_RECT: Quad = Quad(
        Pt(0.0, 0.0),
        Pt(PAGE_WIDTH, 0.0),
        Pt(PAGE_WIDTH, PAGE_HEIGHT),
        Pt(0.0, PAGE_HEIGHT),
    )

    fun solid(width: Int, height: Int, color: Int): RgbaImage = RgbaImage.filled(width, height, color)

    /** Fills a convex quadrilateral with a flat colour using pixel-centre testing. */
    fun fillQuad(image: RgbaImage, quad: Quad, color: Int) {
        val ordered = if (quad.isConvex()) quad else Quad.order(quad.points)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (ordered.containsPoint(Pt(x + 0.5, y + 0.5))) image.set(x, y, color)
            }
        }
    }

    /**
     * Renders a photograph of a page: a light quadrilateral on a dark background with
     * evenly spaced text bars inside it. Because the bars are laid out in page space and
     * mapped through the same homography as the outline, the result is geometrically
     * consistent with a real capture.
     */
    fun documentOnBackground(
        canvasWidth: Int,
        canvasHeight: Int,
        pageQuad: Quad,
        background: Int = 0xFF303030.toInt(),
        paper: Int = 0xFFF4F4F4.toInt(),
        ink: Int = 0xFF141414.toInt(),
        textLines: Int = 8,
        margin: Double = 34.0,
        barHeight: Double = 7.0,
        lineGap: Double = 46.0,
    ): RgbaImage {
        val image = solid(canvasWidth, canvasHeight, background)
        fillQuad(image, pageQuad, paper)

        // Canvas -> page-space inverse mapping.
        val inverse = Homography.solve(pageQuad.points, PAGE_RECT.points)

        // Pre-compute the text bars in page space.
        val bars = ArrayList<DoubleArray>() // x0, y0, x1, y1
        var y = 40.0
        var index = 0
        while (y + barHeight < PAGE_HEIGHT - margin && bars.size < textLines) {
            val x0 = margin
            // Ragged right edge, like real prose.
            val x1 = PAGE_WIDTH - margin - if (index % 3 == 2) 80.0 else 0.0
            bars.add(doubleArrayOf(x0, y, x1, y + barHeight))
            y += lineGap
            index++
        }

        for (py in 0 until canvasHeight) {
            for (px in 0 until canvasWidth) {
                if (!pageQuad.containsPoint(Pt(px + 0.5, py + 0.5))) continue
                val page = Homography.map(inverse, Pt(px + 0.5, py + 0.5))
                for (bar in bars) {
                    if (page.x >= bar[0] && page.x <= bar[2] && page.y >= bar[1] && page.y <= bar[3]) {
                        image.set(px, py, ink)
                        break
                    }
                }
            }
        }
        return image
    }

    /** Smallest achievable maximum corner separation over all corner pairings. */
    fun maxCornerError(a: Quad, b: Quad): Double {
        val pa = a.points
        val pb = b.points
        var best = Double.MAX_VALUE
        for (perm in permutations(4)) {
            var worst = 0.0
            for (i in 0 until 4) {
                val d = pa[i].dist(pb[perm[i]])
                if (d > worst) worst = d
            }
            if (worst < best) best = worst
        }
        return best
    }

    private fun permutations(n: Int): List<IntArray> {
        val result = ArrayList<IntArray>()
        val current = IntArray(n) { it }

        fun swap(i: Int, j: Int) {
            val t = current[i]; current[i] = current[j]; current[j] = t
        }

        fun generate(k: Int) {
            if (k == 1) {
                result.add(current.copyOf())
                return
            }
            generate(k - 1)
            for (i in 0 until k - 1) {
                if (k % 2 == 0) swap(i, k - 1) else swap(0, k - 1)
                generate(k - 1)
            }
        }
        generate(n)
        return result
    }
}
