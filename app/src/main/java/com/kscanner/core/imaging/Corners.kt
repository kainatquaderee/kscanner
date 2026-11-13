package com.kscanner.core.imaging

/**
 * Packs a document outline into a string for storage, and unpacks it again.
 * Format: `x,y;x,y;x,y;x,y` in tl;tr;br;bl order.
 */
object Corners {

    fun serialize(quad: Quad): String =
        quad.points.joinToString(";") { "${it.x},${it.y}" }

    fun parse(text: String?): Quad? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(';')
        if (parts.size != 4) return null

        val points = ArrayList<Pt>(4)
        for (part in parts) {
            val xy = part.split(',')
            if (xy.size != 2) return null
            val x = xy[0].trim().toDoubleOrNull() ?: return null
            val y = xy[1].trim().toDoubleOrNull() ?: return null
            if (x.isNaN() || y.isNaN()) return null
            points.add(Pt(x, y))
        }
        return Quad(points[0], points[1], points[2], points[3])
    }
}
