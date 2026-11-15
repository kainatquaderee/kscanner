package com.kscanner.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import com.kscanner.core.imaging.PreviewViewport
import com.kscanner.core.imaging.ViewportRect
import com.kscanner.core.imaging.Viewports
import kotlin.math.abs

/**
 * The zoom and pan of one surface, held as Compose state so the picture on screen follows the
 * fingers without the screen having to keep three loose values per stage.
 *
 * Every mutation goes through [apply], which means the pinch, the drag, the double tap and the
 * screen's own reset all land on the arithmetic in [Viewports] instead of on a private fourth
 * copy of the rules.
 */
@Stable
class ZoomState {

    var scale by mutableFloatStateOf(1f)
        private set

    var offsetX by mutableFloatStateOf(0f)
        private set

    var offsetY by mutableFloatStateOf(0f)
        private set

    /** The current transform, for handing to [Viewports]. */
    val viewport: PreviewViewport get() = PreviewViewport(scale, offsetX, offsetY)

    fun apply(next: PreviewViewport) {
        scale = next.scale
        offsetX = next.offsetX
        offsetY = next.offsetY
    }

    /** Back to the natural size in the middle of the surface. */
    fun reset() = apply(PreviewViewport())
}

/**
 * Lets the content under a zoom gesture take a drag before the viewport pans.
 *
 * The crop stage is the reason this exists: a drag that starts on a corner handle has to move that
 * corner, while a drag that starts anywhere else has to pan the picture. Positions are in the
 * surface's own coordinates — the space the zoom layer scales — so a hit test written against what
 * the canvas drew needs no conversion, only the touch radius divided by the current zoom.
 */
fun interface DragClaim {

    /**
     * Called once per gesture with the point the finger landed on. Returning `true` means the
     * content owns the drag: no panning and no zooming until the finger lifts.
     */
    fun onStart(position: Offset): Boolean

    /** Called for every move of a drag the content claimed. */
    fun onDrag(position: Offset) {}

    /** Called when a claimed drag ends, so the content can release its handle. */
    fun onEnd() {}

    companion object {
        /** A surface whose content never claims a drag, so every gesture zooms or pans. */
        val None: DragClaim = DragClaim { false }
    }
}

/**
 * Makes [state] follow the pinch, pan and double tap gestures of the surface it is applied to.
 *
 * Two gesture nodes are installed on purpose, and they cannot be one: a tap has to survive the
 * wait for a second finger, while a drag has to be reported from the moment it starts. `claim` is
 * consulted first so the crop handles and the pan can never both act on the same finger.
 *
 * [aspect] is the picture's width over height. It is only needed to know where the picture sits
 * inside its frame, which is the box [Viewports.clamp] keeps the zoomed picture covering.
 *
 * The modifier has to sit *inside* any `graphicsLayer` that shows [state]: finger positions arrive
 * in the coordinates of the node they are delivered to, with the layers above it already undone,
 * so this is the space the picture itself is measured in.
 */
@Composable
fun Modifier.zoomable(
    state: ZoomState,
    aspect: Float,
    claim: DragClaim = DragClaim.None,
): Modifier {
    val latestState by rememberUpdatedState(state)
    val latestAspect by rememberUpdatedState(aspect)
    val latestClaim by rememberUpdatedState(claim)
    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)

                if (latestClaim.onStart(down.position)) {
                    down.consume()
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        // Only the finger that claimed the drag is followed: a second finger landing
                        // mid-drag is not a pinch, it is a hand holding the device.
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            pressed = false
                        } else {
                            latestClaim.onDrag(change.position)
                            change.consume()
                        }
                    }
                    latestClaim.onEnd()
                    return@awaitEachGesture
                }

                var zoom = 1f
                var pan = Offset.Zero
                var pastTouchSlop = false
                val touchSlop = viewConfiguration.touchSlop
                var cancelled = false
                do {
                    val event = awaitPointerEvent()
                    cancelled = event.changes.any { it.isConsumed }
                    if (!cancelled) {
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (!pastTouchSlop) {
                            // A pinch and a drag both start below the slop; the accumulated motion
                            // decides which of them the user meant.
                            zoom *= zoomChange
                            pan += panChange
                            val centroidSize = event.calculateCentroidSize(useCurrent = false)
                            if (abs(1f - zoom) * centroidSize > touchSlop || pan.getDistance() > touchSlop) {
                                pastTouchSlop = true
                            }
                        }

                        if (pastTouchSlop && (zoomChange != 1f || panChange != Offset.Zero)) {
                            val current = latestState.viewport
                            val surface = Surface(size, latestAspect)
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val focus = if (centroid.isSpecified) {
                                surface.toScreen(centroid, current)
                            } else {
                                // No pointer survived from the previous event, so there is no
                                // meaningful centre to anchor on; the surface's own centre will do.
                                Offset(surface.viewport.centerX, surface.viewport.centerY)
                            }
                            latestState.apply(
                                Viewports.gesture(
                                    current = current,
                                    zoom = zoomChange,
                                    // The offsets live in screen pixels while the fingers are reported
                                    // in the surface's own, unscaled ones: one screen pixel is
                                    // `scale` of these here.
                                    panX = panChange.x * current.scale,
                                    panY = panChange.y * current.scale,
                                    focusX = focus.x,
                                    focusY = focus.y,
                                    viewport = surface.viewport,
                                    content = surface.content,
                                ),
                            )
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                } while (!cancelled && event.changes.any { it.pressed })
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { position ->
                    val current = latestState.viewport
                    val surface = Surface(size, latestAspect)
                    val tap = surface.toScreen(position, current)
                    latestState.apply(
                        Viewports.doubleTap(
                            current = current,
                            tapX = tap.x,
                            tapY = tap.y,
                            viewport = surface.viewport,
                            content = surface.content,
                        ),
                    )
                },
            )
        }
}

/**
 * A finished page that can be pinched, panned and double tapped.
 *
 * The gestures sit on the picture's own node rather than on its parent, so the area that responds
 * is exactly the picture's frame: identical to the parent at 1x, and stopping at the picture's edge
 * once zoomed instead of creeping over the controls around it.
 */
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    state: ZoomState,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .graphicsLayer {
                scaleX = state.scale
                scaleY = state.scale
                translationX = state.offsetX
                translationY = state.offsetY
                clip = true
            }
            // Ordered after the layer so that finger positions arrive already unscaled.
            .zoomable(state, bitmap.width.toFloat() / bitmap.height),
    )
}

/**
 * The two rectangles a gesture needs, plus the mapping between the surface's own coordinates and
 * the screen ones the offsets are kept in.
 *
 * Fingers are reported in the space the zoom layer scales, so every position a gesture reads has to
 * be pushed through the very transform that space is being shown with before it can be used as a
 * focus point.
 */
private class Surface(size: IntSize, aspect: Float) {

    val viewport = ViewportRect(0f, 0f, size.width.toFloat(), size.height.toFloat())

    val content: ViewportRect = Viewports.fitRect(viewport, aspect)

    /** Where the picture point [position] is being drawn, given the transform in [state]. */
    fun toScreen(position: Offset, state: PreviewViewport): Offset = Offset(
        viewport.centerX + (position.x - viewport.centerX) * state.scale + state.offsetX,
        viewport.centerY + (position.y - viewport.centerY) * state.scale + state.offsetY,
    )
}
