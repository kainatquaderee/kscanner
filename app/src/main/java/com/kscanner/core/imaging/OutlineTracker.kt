package com.kscanner.core.imaging

/**
 * Decides which outline the live camera preview should be drawing.
 *
 * Detection gives an answer per frame, but drawing every answer would be unwatchable: the
 * outline would shimmer with the camera's noise and jump whenever a line was briefly lost. The
 * outline on screen therefore has to be a *memory* of detections, and that memory is what this
 * class is: it takes an answer per frame and produces something calm.
 *
 * Five rules. The deadband and the tween cadence are the same figures a competing scanner uses in
 * its own live preview; the other two are chosen from what detection costs and how it fails here:
 *
 * 1. **Look, don't stare.** A detection is only asked for every [intervalMillis]; frames in
 *    between are dropped. Detection costs tens of milliseconds on a phone, which would otherwise
 *    eat the preview's frame budget.
 * 2. **Ignore the tremor, and the change of mind.** If the new outline is within [deadbandPx] of
 *    the one being followed, it is the same outline seen through noise, and it is not worth
 *    moving for. Anything further is followed only if it continues the outline already on screen
 *    ([OutlineChooser]); moving to it is a tween over [tweenMillis] rather than a snap, so the
 *    border slides after the page instead of flickering between positions.
 * 3. **Silence and disagreement are different things.** One bad frame is not the user covering
 *    the lens, so a frame with no plausible outline starts a clock rather than erasing anything;
 *    a frame whose plausible outline is elsewhere is evidence of a page, not news about where,
 *    and holds the outline it has. Only [holdMillis] with no evidence at all takes the pen down.
 * 4. **But a disagreement nobody withdraws is a change of mind.** The same outline elsewhere, said
 *    again for [holdMillis], is believed: a page that has moved somewhere else and stayed there is
 *    not a flicker. Two descriptions of one page trading places never get that far, because they
 *    are not the same description — which is what keeps the swap out of this rule.
 * 5. **A guess is not drawn.** An answer below [minConfidence] may hold the outline up but never
 *    moves it: a border drawn confidently in the wrong place is worse than the one already there.
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
    /** Below this, an answer is not even evidence that a page is in view. */
    private val evidenceFloor: Double = OutlineChooser.DEFAULT_FLOOR,
) {

    /** One move of the outline: [from] at [startedAt], reaching [to] [tweenMillis] later. */
    private data class Tween(val from: Quad, val to: Quad, val startedAt: Long)

    /**
     * A run of frames that say "a page is here, but not where the outline is": the outline they
     * keep describing, and when the run started. A run is broken by a frame that agrees with the
     * shown outline, and also by a frame that disagrees about somewhere else entirely.
     */
    private data class Disagreement(val quad: Quad, val since: Long)

    private var tween: Tween? = null

    /** What the last call to [sample] produced; null when nothing is being drawn. */
    private var shown: Quad? = null

    /** The run of disagreement under way, if any. */
    private var disagreement: Disagreement? = null

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
        accept(listOfNotNull(detection), frame, viewWidth, viewHeight, now)
    }

    /**
     * Offers every outline this frame supports, best first, as [DocDetector.candidates] reports
     * them. One answer per frame is not enough: the detector's ranking of two descriptions of the
     * same page can swap while the page stays put, and following the swap is what drags the
     * outline across the frame. [OutlineChooser] weighs them against the outline already on
     * screen, and the cases are kept apart here:
     *
     *  * nothing plausible — no evidence, so the hold keeps running down;
     *  * something plausible that does not continue the outline — a page is in view, but where it
     *    is, is not news: the hold is refreshed and the outline stays put — until the same
     *    disagreement has been reported for [holdMillis], at which point it is a change of mind and
     *    the outline goes to it (see [yielded]);
     *  * a candidate that continues the outline, confidently — the outline moves to it.
     *
     * Below [minConfidence] an answer is only ever evidence. It is still worth the hold: a
     * detector unsure of the page's edges has not seen the page leave.
     */
    @Synchronized
    fun accept(candidates: List<Detection>, frame: PreviewFrame, viewWidth: Int, viewHeight: Int, now: Long) {
        if (viewWidth <= 0 || viewHeight <= 0) return

        val inView = candidates.map { candidate ->
            candidate.copy(quad = FrameGeometry.toView(candidate.quad, frame, viewWidth, viewHeight))
        }
        // Judged against where the outline has been *told* to go, not against the position the
        // tween happens to have reached: the glide lags a moving page by design, and measuring
        // continuity against a lagging position would answer "no" to a page that is simply moving.
        val choice = OutlineChooser.choose(inView, tween?.to ?: shown, floor = evidenceFloor)
        if (choice is OutlineChooser.Choice.Nothing) return

        // Something in view is evidence the page is still there, whichever way the choice went.
        lastAccepted = now

        when (choice) {
            is OutlineChooser.Choice.Follow -> {
                disagreement = null
                if (choice.detection.confidence < minConfidence) return
                moveTo(choice.detection.quad, now)
            }

            is OutlineChooser.Choice.Disagree -> {
                val best = yielded(choice.detection, now) ?: return
                if (best.confidence < minConfidence) return
                disagreement = null
                moveTo(best.quad, now)
            }

            // Unreachable — returned above — but the when must cover the type.
            OutlineChooser.Choice.Nothing -> return
        }
    }

    /**
     * How long the outline may stand contradicted by the same plausible outline elsewhere before
     * that contradiction is believed. The hold is the figure for the opposite question — how long
     * an outline survives on no evidence at all — and disagreement is measured on the same clock:
     * a page that has moved somewhere else and stayed there for that long is not a flicker.
     *
     * The run has to be the *same* description throughout. Two hypotheses of one page trading
     * places never build a run, because each is a different place, which is exactly the case that
     * must not be believed; a hand that re-aims describes the new position the same way from frame
     * to frame, which is exactly the case that must.
     *
     * Returns the outline to adopt when the run has outlasted [holdMillis], or null while it has
     * not — having recorded or restarted the run either way.
     */
    private fun yielded(detection: Detection, now: Long): Detection? {
        val run = disagreement?.takeIf { OutlineChooser.continues(it.quad, detection.quad) }
            ?.copy(quad = detection.quad)
            ?: Disagreement(detection.quad, now)
        disagreement = run
        if (now - run.since < holdMillis) return null
        return detection
    }

    /** Follows [mapped], tweening from wherever the outline has got to. */
    private fun moveTo(mapped: Quad, now: Long) {
        val current = tween
        if (current == null) {
            // First outline of this run: it appears where it is, rather than sliding in from
            // wherever the previous one happened to be.
            tween = Tween(mapped, mapped, now)
            shown = mapped
            return
        }
        if (mapped.cornerDistance(current.to) <= deadbandPx) return

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

    companion object {
        /**
         * Ten detections a second. What matters is not the rate itself but the size of each step:
         * the page moves in the gap between two detections, so the distance the outline has to
         * cover is the page's speed times this interval. At 150 ms a hand crossing the frame moved
         * the outline in visible strides. The competing scanner avoids that by detecting again as
         * soon as the previous detection returns rather than on a timer — one detection in flight,
         * no more; ten a second is as close to that as the detector's cost allows here.
         */
        const val DEFAULT_INTERVAL_MILLIS: Long = 100

        /**
         * A little below the detector's own accept threshold (0.52): live, an outline has to be
         * plausible rather than provable, and waiting for proof would leave the border missing on
         * exactly the frames where the user most wants to see it.
         */
        const val DEFAULT_MIN_CONFIDENCE: Double = 0.45

        /**
         * Longer than [DEFAULT_INTERVAL_MILLIS], deliberately. The competing scanner's border
         * takes 180 ms to reach a new position, which is more than the gap between its detections,
         * so its outline is never at rest while the page is moving — it glides. A tween that
         * finishes before the next detection arrives does the opposite: it arrives, sits still, and
         * then sets off again, which is what makes a border read as stepping rather than following.
         */
        const val DEFAULT_TWEEN_MILLIS: Long = 180

        /**
         * Long enough to ride out a blurred frame or a lost line, short enough to feel honest.
         *
         * It also bounds how long the outline may stand contradicted by one plausible outline
         * somewhere else, which is the other way the border can go wrong: a frozen border stays
         * wrong forever, and the page it was on is gone.
         */
        const val DEFAULT_HOLD_MILLIS: Long = 400
    }
}
