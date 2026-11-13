package com.kscanner.core.imaging

/**
 * Decides when the camera should fire on its own.
 *
 * An automatic shutter is only pleasant if it is not twitchy: a single lucky frame is often
 * a half-settled hand, and firing again the instant the page turns gives a burst of
 * duplicates. So the gate wants several *consecutive* good frames and then stays shut for a
 * cooldown, during which the photographer can move the page on.
 *
 * The measurements come from [Quality], so "good" means the same thing here as it does in
 * the on-screen advice: sharp enough, lit evenly enough, and actually showing a document.
 */
class CaptureGate(
    /** Consecutive good frames required before firing. */
    private val requiredFrames: Int = 5,
    /** Frames to ignore after a shot, so the next page has time to arrive. */
    private val cooldownFrames: Int = 12,
    /** Stricter than the advice threshold: an automatic shot should not be borderline. */
    private val minSharpness: Double = Quality.SHARPNESS_GOOD,
) {

    private var streak = 0
    private var cooldown = 0
    private var lastAdvice: CaptureAdvice = CaptureAdvice.NO_DOCUMENT

    /** The most recent advice, for an on-screen hint. */
    val advice: CaptureAdvice get() = lastAdvice

    /** 0..1 progress toward firing, so the UI can draw a ring that fills. */
    val readiness: Double get() = if (cooldown > 0) 0.0 else {
        (streak.toDouble() / requiredFrames).coerceIn(0.0, 1.0)
    }

    /**
     * Feeds one analysis frame.
     *
     * @return true on exactly the frame where the shutter should fire.
     */
    fun observe(report: Quality.Report): Boolean {
        lastAdvice = report.advice
        if (cooldown > 0) {
            cooldown--
            streak = 0
            return false
        }
        if (report.advice != CaptureAdvice.GOOD || report.sharpness < minSharpness) {
            streak = 0
            return false
        }
        streak++
        if (streak < requiredFrames) return false
        streak = 0
        cooldown = cooldownFrames
        return true
    }

    /** Restarts the sequence, e.g. after a manual shot. */
    fun reset() {
        streak = 0
        cooldown = 0
        lastAdvice = CaptureAdvice.NO_DOCUMENT
    }
}
