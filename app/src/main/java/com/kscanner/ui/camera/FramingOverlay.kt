package com.kscanner.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kscanner.core.imaging.Quad
import com.kscanner.ui.theme.ScanTealLight

/**
 * Marks the outline the detector is following, over the live preview.
 *
 * Two things are drawn and both say something. The shade outside the outline is the promise:
 * crop to this and the rest of the world is gone. The bright edge is the outline itself, at a
 * weight thin enough not to hide the paper's own margin.
 *
 * [outline] is in view pixels — [com.kscanner.core.imaging.FrameGeometry] has already turned the
 * detector's frame coordinates into the preview's — so this composable only draws. A null outline
 * is a frame the detector is not sure about, and it draws nothing rather than guessing.
 */
@Composable
fun FramingOverlay(outline: Quad?, modifier: Modifier = Modifier) {
    if (outline == null) return
    Canvas(modifier) {
        val corners = outline.points
        val edge = Path().apply {
            moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
            for (i in 1 until corners.size) lineTo(corners[i].x.toFloat(), corners[i].y.toFloat())
            close()
        }
        // Even-odd over the whole canvas and the outline together: the classic hole-punch, and
        // the only part of the canvas that stays undimmed is the page.
        val shade = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addPath(edge)
        }

        drawPath(shade, Color.Black.copy(alpha = SHADE_ALPHA))
        drawPath(
            path = edge,
            color = ScanTealLight,
            style = Stroke(width = BORDER_WIDTH.dp.toPx(), join = StrokeJoin.Round),
        )
    }
}

/** Dark enough to blot out the surroundings, light enough to still see what is being cropped. */
private const val SHADE_ALPHA = 0.42f

private const val BORDER_WIDTH = 2
