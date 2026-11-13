package com.kscanner.core.ocr

/**
 * One recognised line of text together with its bounding box in processed-image pixels.
 * Keeping the geometry (not just the string) is what makes a real searchable PDF possible:
 * the invisible text layer can be positioned to match the visible glyphs.
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/** Joins OCR lines into a paragraph-preserving plain-text representation. */
object OcrText {

    fun plainText(lines: List<OcrLine>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        var previousBottom = lines.first().bottom
        for (line in lines) {
            val gap = line.top - previousBottom
            if (sb.isNotEmpty()) {
                if (gap > line.height * 2) sb.append("\n\n") else sb.append('\n')
            }
            sb.append(line.text.trim())
            previousBottom = line.bottom
        }
        return sb.toString().trim()
    }

    /**
     * Storage format: one line per record, `left|top|right|bottom|text`, with the field
     * separator and newlines escaped. Deliberately hand-rolled: it keeps the OCR layer
     * free of both a JSON dependency and Android framework classes.
     */
    fun encode(lines: List<OcrLine>): String = lines.joinToString("\n") { line ->
        val safe = buildString {
            for (c in line.text) {
                when (c) {
                    '\\' -> append("\\\\")
                    '|' -> append("\\p")
                    '\n' -> append("\\n")
                    '\r' -> Unit
                    else -> append(c)
                }
            }
        }
        "${line.left}|${line.top}|${line.right}|${line.bottom}|$safe"
    }

    fun decode(encoded: String?): List<OcrLine> {
        if (encoded.isNullOrBlank()) return emptyList()
        val result = ArrayList<OcrLine>()
        for (record in encoded.split("\n")) {
            if (record.isBlank()) continue
            val parts = record.split("|", limit = 5)
            if (parts.size != 5) continue
            val left = parts[0].toIntOrNull() ?: continue
            val top = parts[1].toIntOrNull() ?: continue
            val right = parts[2].toIntOrNull() ?: continue
            val bottom = parts[3].toIntOrNull() ?: continue

            // Single unescape pass: an escape is always a backslash plus one control letter.
            val raw = parts[4]
            val text = buildString {
                var i = 0
                while (i < raw.length) {
                    val c = raw[i]
                    if (c == '\\' && i + 1 < raw.length) {
                        when (raw[i + 1]) {
                            'p' -> { append('|'); i += 2 }
                            'n' -> { append('\n'); i += 2 }
                            '\\' -> { append('\\'); i += 2 }
                            else -> { append(c); i++ }
                        }
                    } else {
                        append(c)
                        i++
                    }
                }
            }
            if (text.isBlank()) continue
            result.add(OcrLine(text, left, top, right, bottom))
        }
        return result
    }
}