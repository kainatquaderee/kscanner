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
import com.kscanner.core.imaging.Quality

/**
 * Watches the preview stream. It does two jobs on every frame, from the same grayscale image:
 *
 *  * measures the frame for the auto-capture gate, which owns the all-important "is this a
 *    genuine, settled shot" decision so that it can be unit-tested without a camera;
 *  * finds the page outline for the framing overlay, but only as often as the [tracker] asks
 *    for it — detection costs tens of milliseconds, and the overlay is not worth a dropped
 *    preview frame.
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

            val report = Quality.analyse(gray)
            val ready = gate.observe(report)
            onAdvice(report.advice, gate.readiness)
            if (ready) onFire()

            val now = elapsed()
            if (tracker.dueForDetection(now)) {
                // The fallbacks are for a still photograph, where an outline around the ink is
                // better than none: a live border hugging the text would read as the page edge.
                val found = DocDetector.candidates(gray, allowFallbacks = false)
                if (found.isNotEmpty()) onCandidates(found, sampled.frame)
            }
        } catch (_: Throwable) {
            // A dropped frame is never worth crashing the camera over.
        } finally {
            image.close()
        }
    }
}
