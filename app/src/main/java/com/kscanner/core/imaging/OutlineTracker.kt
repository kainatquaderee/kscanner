package com.kscanner.core.imaging

import kotlin.math.max

/**
 * Decides which outline the live camera preview should be drawing.
 *
 * Detection gives an answer per frame, but drawing every answer would be unwatchable: the
 * outline would shimmer with the camera's noise and jump whenever a line was briefly lost. The
 * outline on screen therefore has to be a *memory* of detections, and that memory is what this
 * class is: it takes an answer per frame and produces something calm.
 *
 * Three rules. Two of them — the deadband and the tween cadence — are the same figures a
 * competing scanner uses in its own live preview; the third is chosen from what detection costs
 * here:
 *
 * 1. **Look, don't stare.** A detection is only asked for every [intervalMillis]; frames in
 *    between are dropped. Detection costs tens of milliseconds on a phone, which would otherwise
 *    eat the preview's frame budget.
 * 2. **Ignore the tremor.** If the new outline is within [deadbandPx] of the one being followed,
 *    it is the same outline seen through noise, and it is not worth moving for. Anything larger
 *    is followed, but by tweening over [tweenMillis] rather than snapping, so the border slides
 *    after the page instead of flickering between positions.
 * 3. **Drop the pen when the page goes.** A detection that is missing, or too unsure to trust,
 *    does not erase the outline on the spot — one bad frame is not the user covering the lens.
 *    After [holdMillis] with nothing trustworthy, the outline goes.
 *
 * Everything here is coordinates and time: no camera, no view, no threads of its own. The caller
 * feeds it detections from whatever thread it detects on and reads the current outline before
 * drawing. Both are safe to call from different threads.
 */
class OutlineTracker(
    /** Corners this close to the outline being followed are treated as the same outline. */
    private val deadbandPx: Double,
    /** How often a detection is worth doing. */
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    /** Below this, a detection is not shown: a guess drawn confidently is worse than no outline. */
    private val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    /** How long a followed outline takes to reach a new position. */
    private val tweenMillis: Long = DEFAULT_TWEEN_MILLIS,
    /** How long the last outline survives after detections stop being trustworthy. */
    private val holdMillis: Long = DEFAULT_HOLD_MILLIS,
) {

    /** One move of the outline: [from] at [startedAt], reaching [to] [tweenMillis] later. */
    private data class Tween(val from: Quad, val to: Quad, val startedAt: Long)

    private var tween: Tween? = null

    /** What the last call to [sample] produced; null when nothing is being drawn. */
    private var shown: Quad? = null

    /** When a detection was last accepted; drives the hold. */
    private var lastAccepted = 0L

    /** When a detection was last asked for; drives the interval. */
    private var lastAsked = Long.MIN_VALUE

    /**
     * True when it is time to detect again. Call once per frame; every call that returns false
     * is a frame whose detection can be skipped outright.
     */
    @Synchronized
    fun dueForDetection(now: Long): Boolean {
        if (lastAsked != Long.MIN_VALUE && now - lastAsked < intervalMillis) return false
        lastAsked = now
        return true
    }

    /**
     * Offers a detection made on [frame]. [viewWidth] and [viewHeight] are the size of the
     * surface the preview is drawing into; until they are known there is nowhere to draw, so the
     * detection is dropped rather than drawn in the wrong place.
     */
    @Synchronized
    fun accept(detection: Detection?, frame: PreviewFrame, viewWidth: Int, viewHeight: Int, now: Long) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        if (detection == null || detection.confidence < minConfidence) return

        val mapped = FrameGeometry.toView(detection.quad, frame, viewWidth, viewHeight)
        lastAccepted = now

        val current = tween
        if (current == null) {
            // First outline of this run: it appears where it is, rather than sliding in from
            // wherever the previous one happened to be.
            tween = Tween(mapped, mapped, now)
            shown = mapped
            return
        }
        if (cornersApart(current.to, mapped) <= deadbandPx) return

        // Followed from where the outline is now, so a second move mid-tween continues the
        // slide instead of snapping back to the first position.
        tween = Tween(positionAt(current, now), mapped, now)
    }

    /** The outline to draw at [now], in view pixels, or null when there is nothing to draw. */
    @Synchronized
    fun sample(now: Long): Quad? {
        if (shown != null && now - lastAccepted > holdMillis) {
            // Nothing trustworthy for a while. The pen goes down, whichever position the outline
            // had reached: a stale border over a page that is no longer there is worse than none.
            tween = null
            shown = null
            return null
        }
        val current = tween ?: return null
        return positionAt(current, now).also { shown = it }
    }

    /** Where the outline has got to at [now]. */
    private fun positionAt(t: Tween, now: Long): Quad {
        if (tweenMillis <= 0) return t.to
        val f = ((now - t.startedAt).toDouble() / tweenMillis).coerceIn(0.0, 1.0)
        return Quad(
            Pt.lerp(t.from.tl, t.to.tl, f),
            Pt.lerp(t.from.tr, t.to.tr, f),
            Pt.lerp(t.from.br, t.to.br, f),
            Pt.lerp(t.from.bl, t.to.bl, f),
        )
    }

    /** The furthest any one corner has to travel to turn [a] into [b]. */
    private fun cornersApart(a: Quad, b: Quad): Double {
        var worst = 0.0
        for (i in 0 until 4) worst = max(worst, a.points[i].dist(b.points[i]))
        return worst
    }

    companion object {
        /** Roughly seven detections a second: enough to track a hand, cheap enough to ignore. */
        const val DEFAULT_INTERVAL_MILLIS: Long = 150

        /**
         * A little below the detector's own accept threshold (0.52): live, an outline has to be
         * plausible rather than provable, and waiting for proof would leave the border missing on
         * exactly the frames where the user most wants to see it.
         */
        const val DEFAULT_MIN_CONFIDENCE: Double = 0.45

        /** Eight frames or so, which reads as sliding rather than jumping. */
        const val DEFAULT_TWEEN_MILLIS: Long = 120

        /** Long enough to ride out a blurred frame or a lost line, short enough to feel honest. */
        const val DEFAULT_HOLD_MILLIS: Long = 400
    }
}
