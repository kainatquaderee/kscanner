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
     * A found outline, with the frame it was found in. Only confident answers are reported:
     * a missing detection is not news, since the tracker keeps drawing what it has.
     */
    private val onOutline: (Detection, PreviewFrame) -> Unit,
    /** Longest edge of the analysed frame. Small on purpose: this runs on every frame. */
    private val workingEdge: Int = 320,
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val step = Luma.stepFor(image.width, image.height, workingEdge)
            val gray = Luma.grayFromPlane(
                buffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                step = step,
            )

            val report = Quality.analyse(gray)
            val ready = gate.observe(report)
            onAdvice(report.advice, gate.readiness)
            if (ready) onFire()

            val now = elapsed()
            if (tracker.dueForDetection(now)) {
                // The fallbacks are for a still photograph, where an outline around the ink is
                // better than none: a live border hugging the text would read as the page edge.
                DocDetector.detect(gray, allowFallbacks = false)?.let { found ->
                    onOutline(found, PreviewFrame(image.width, image.height, image.imageInfo.rotationDegrees))
                }
            }
        } catch (_: Throwable) {
            // A dropped frame is never worth crashing the camera over.
        } finally {
            image.close()
        }
    }
}
