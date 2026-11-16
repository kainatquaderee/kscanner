package com.kscanner.core.imaging

/**
 * Decides when the camera should fire on its own.
 *
 * An automatic shutter is only pleasant if it is not twitchy: a single lucky frame is often a
 * half-settled hand, and firing again the instant the page turns gives a burst of duplicates. So
 * the gate counts *consecutive stable observations of an acceptable page* and then stays shut for a
 * cooldown, during which the photographer can move the page on.
 *
 * Two things make that count mean something. It is counted in **detections**, not analysis frames —
 * a new outline only arrives every [OutlineTracker.DEFAULT_INTERVAL_MILLIS], so counting frames
 * would measure the run in a unit nobody chose — and "stable" is measured against an **anchor**, the
 * outline that started the run, rather than against whatever the previous detection was
 * ([OutlineChooser.continues]' opposite number). Against the previous detection, a page drifting
 * slowly across the preview resets the run every few detections and never fires.
 *
 * Stability alone is not enough either: a page held steady out of focus, in the dark or under a
 * glare is stable, and a shot of it is not worth taking. The page region's advice has to be
 * [CaptureAdvice.GOOD] as well, which is the same figure the screen shows, from the same
 * measurement ([Quality.analysePage]) — see [Quality] for why the page's sharpness is judged against
 * a different line than a whole frame's.
 */
class CaptureGate(
    /**
     * Consecutive stable detections required before firing. Five is about half a second at the
     * tracker's ten detections a second: long enough that a hand passing over the paper does not
     * trip it, short enough that holding the page still is the faster way to take the shot.
     */
    private val requiredRun: Int = 5,
    /** Detections to ignore after a shot, so the next page has time to arrive. */
    private val cooldownDetections: Int = 12,
    /**
     * How far a detection may sit from the anchor and still be the same page held still, as a
     * fraction of the anchor's own span. The chooser's continuity figure, and for the same reason:
     * a fraction of the page travels the same meaning whether the page fills the preview or sits
     * small in it, and whether the frame is 320 px or 640 px.
     */
    private val cornerTolerance: Double = OutlineChooser.DEFAULT_CONTINUITY,
) {

    /** The outline that started the current run; the run is measured from it, not from frame to frame. */
    private var anchor: Quad? = null
    private var run = 0
    private var cooldown = 0
    private var lastAdvice: CaptureAdvice = CaptureAdvice.NO_DOCUMENT

    /** The most recent advice, for an on-screen hint. */
    val advice: CaptureAdvice get() = lastAdvice

    /** 0..1 progress toward firing, so the UI can draw a ring that fills. */
    val readiness: Double get() = if (cooldown > 0) 0.0 else {
        (run.toDouble() / requiredRun).coerceIn(0.0, 1.0)
    }

    /**
     * Feeds one analysis frame.
     *
     * @param region the quality of the page region being followed, or of the frame when there is no
     *   page; measured afresh every frame, since it is what the advice is.
     * @param outline the outline this frame's detection found, in the sampled frame's pixels, or
     *   null when the page is gone. Only read when [isDetection] is true.
     * @param isDetection whether an outline was asked for on this frame. A detection is what moves
     *   the run; the frames between two of them have nothing new to say about where the page is.
     * @return true on exactly the frame where the shutter should fire.
     */
    fun observe(region: Quality.Report, outline: Quad?, isDetection: Boolean): Boolean {
        lastAdvice = region.advice
        if (!isDetection) return false

        if (cooldown > 0) {
            cooldown--
            forget()
            return false
        }

        // Nothing found: the page has gone, or gone out of reach. Either way there is nothing to be
        // steady *about*, and the next outline to appear starts a run of its own from scratch.
        if (outline == null) {
            forget()
            return false
        }

        val current = anchor
        if (current != null && OutlineChooser.continues(current, outline, cornerTolerance)) {
            run++
        } else {
            anchor = outline
            run = 1
        }

        // A stable page that is out of focus or badly lit is stable, and still not worth shooting.
        // The run is not reset for it: the page is where it was, and the fault is the light.
        if (run < requiredRun || region.advice != CaptureAdvice.GOOD) return false

        forget()
        cooldown = cooldownDetections
        return true
    }

    /** Restarts the sequence, e.g. after a manual shot. */
    fun reset() {
        forget()
        cooldown = 0
        lastAdvice = CaptureAdvice.NO_DOCUMENT
    }

    /** Drops the run and its anchor, leaving the cooldown alone. */
    private fun forget() {
        anchor = null
        run = 0
    }
}