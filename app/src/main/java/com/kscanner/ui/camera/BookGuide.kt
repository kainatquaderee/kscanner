package com.kscanner.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp

/**
 * The gutter guide: a dashed line down the horizontal centre of the preview, drawn in Book mode.
 *
 * It exists for alignment. A spread is cut where the gutter is, so the user needs to see where
 * the centre of the frame falls *while framing* rather than discovering after the shot that the
 * fold ran crooked. The line therefore sits exactly where the geometric centre is, and the user
 * moves the book until the fold is under it.
 *
 * It is a line and nothing else: one `Canvas`, no pointer input, no click target, so a tap or a
 * pinch passes straight through it to the camera (a guide that swallowed a touch would be a
 * control, and this is not one). Drawn before the control strips, so it is under them, and only
 * in Book mode — the caller decides that, and the mode changing removes it on the next frame.
 *
 * The dash is deliberately faint: 1 dp of white at 45 %, dashes and gaps of 1 dp, over paper
 * that is usually brighter than the preview's dark surroundings. It has to be findable where it
 * matters — across the gutter — and ignorable everywhere else.
 */
@Composable
fun BookGuide(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = STROKE_WIDTH.dp.toPx()
        val dash = DASH_LENGTH.dp.toPx()
        val x = size.width / 2f
        drawLine(
            color = Color.White.copy(alpha = ALPHA),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
        )
    }
}

/** Faint enough to read as a reference rather than as part of the frame. */
private const val ALPHA = 0.45f

/** Dash and gap are the same length, so the line reads as a dotted rule at any density. */
private const val DASH_LENGTH = 1

private const val STROKE_WIDTH = 1
