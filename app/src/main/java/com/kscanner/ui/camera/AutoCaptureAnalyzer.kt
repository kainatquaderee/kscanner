package com.kscanner.ui.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kscanner.core.imaging.CaptureAdvice
import com.kscanner.core.imaging.CaptureGate
import com.kscanner.core.imaging.Detection
import com.kscanner.core.imaging.DocDetector
import com.kscanner.core.imaging.Luma
import com.kscanner.core.imaging.OutlineTracker
import com.kscanner.core.imaging.PreviewFrame
import com.kscanner.core.imaging.Quad
import com.kscanner.core.imaging.Quality

/**
 * Watches the preview stream. It does two jobs on every frame, from the same grayscale image:
 *
 *  * measures the *page* for the auto-capture gate — the pixels inside the outline being followed,
 *    or the whole frame while there is none — because the gate owns the all-important "is this a
 *    genuine, settled shot" decision, and a form on a large desk or a page on a dark table reads as
 *    a bad *frame* however good the paper in it is. Keeping it here rather than in the gate means
 *    it can be unit-tested without a camera;
 *  * finds the page outline for the framing overlay, but only as often as the [tracker] asks for it
 *    — detection costs tens of milliseconds, and the overlay is not worth a dropped preview frame.
 *
 * The two meet at [lastWinner]: the outline the last detection answered with, in the sampled frame's
 * pixels, which is the region the next frame is measured over. It is the same answer the [tracker]
 * is handed (the detector's own first candidate, so the two cannot disagree about which outline is
 * on screen), kept in frame space rather than mapped back from the view — one source of truth, and
 * no reverse of [com.kscanner.core.imaging.FrameGeometry.toView] to get wrong.
 *
 * The frame's luma plane is already a grayscale image, so no colour conversion is needed.
 *
 * Runs on a background executor. All callbacks are invoked from that thread; the caller is
 * responsible for marshalling them onto the main thread if it needs to.
 */
class AutoCaptureAnalyzer(
    private val gate: CaptureGate,
    private val tracker: OutlineTracker,
    private val onAdvice: (CaptureAdvice, Double) -> Unit,
    private val onFire: () -> Unit,
    /**
     * The frame's outlines, best first, with the frame they were found in — the *sampled* frame,
     * whose pixels they are expressed in. The whole ranked set goes to the tracker rather than its
     * winner: the ranking of two descriptions of the same page can swap between frames while the
     * page stays put, and the tracker is the only place that knows which one is being followed.
     */
    private val onCandidates: (List<Detection>, PreviewFrame) -> Unit,
    /** Longest edge of the analysed frame. Small on purpose: this runs on every frame. */
    private val workingEdge: Int = 320,
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
) : ImageAnalysis.Analyzer {

    /**
     * The outline being followed, in the sampled frame's pixels: the first candidate of the last
     * detection that found anything, null while there is no page or after one has gone. It is what
     * the next frame's page region is measured over, and what the gate is told has been found.
     */
    private var lastWinner: Quad? = null

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            // The sample and the frame it is in are read as one value: the detector answers in the
            // sampled image's pixels, and the drawing side has to be told those pixels' size, not
            // the camera's. Describing one with the other is a scale error of the sampling step,
            // which puts the border beside the page — and, on a phone-shaped view, off the screen.
            val sampled = Luma.sampleFrame(
                buffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                targetEdge = workingEdge,
                rotationDegrees = image.imageInfo.rotationDegrees,
            )
            val gray = sampled.gray

            // Asked once: the answer gates the detection below and is what tells the gate whether a
            // new outline arrived on this frame at all — its run is counted in detections, not in
            // analysis frames, or the frames between two detections would each count as another
            // observation of a page that has not moved.
            val now = elapsed()
            val detecting = tracker.dueForDetection(now)

            // Before the detection, so it is the page as it has been followed up to now: the
            // outline found on *this* frame has not yet been believed by anyone, and measuring over
            // it would make the advice jump to a hypothesis that may not survive the tracker.
            val region = lastWinner
                ?.let { winner -> Quality.analysePage(gray, winner) }
                ?: Quality.analyse(gray)

            var winner: Quad? = null
            if (detecting) {
                // The fallbacks are for a still photograph, where an outline around the ink is
                // better than none: a live border hugging the text would read as the page edge.
                val found = DocDetector.candidates(gray, allowFallbacks = false)
                winner = found.firstOrNull()?.quad
                lastWinner = winner
                if (winner != null) onCandidates(found, sampled.frame)
            }

            val ready = gate.observe(region, winner, detecting)
            onAdvice(region.advice, gate.readiness)
            if (ready) onFire()
        } catch (_: Throwable) {
            // A dropped frame is never worth crashing the camera over.
        } finally {
            image.close()
        }
    }
}