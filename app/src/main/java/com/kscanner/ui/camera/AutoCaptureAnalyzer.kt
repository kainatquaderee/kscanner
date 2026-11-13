package com.kscanner.ui.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kscanner.core.imaging.CaptureAdvice
import com.kscanner.core.imaging.CaptureGate
import com.kscanner.core.imaging.Luma
import com.kscanner.core.imaging.Quality

/**
 * Watches the preview stream and trips the auto-capture gate.
 *
 * The frame's luma plane is already a grayscale image, so no colour conversion is needed;
 * it is subsampled to a small working size, measured by [Quality], and handed to the
 * [CaptureGate], which owns the all-important "is this a genuine, settled shot" decision so
 * that it can be unit-tested without a camera.
 *
 * Runs on a background executor. Both callbacks are invoked from that thread; the caller is
 * responsible for marshalling them onto the main thread if it needs to.
 */
class AutoCaptureAnalyzer(
    private val gate: CaptureGate,
    private val onAdvice: (CaptureAdvice, Double) -> Unit,
    private val onFire: () -> Unit,
    /** Longest edge of the analysed frame. Small on purpose: this runs on every frame. */
    private val workingEdge: Int = 320,
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
        } catch (_: Throwable) {
            // A dropped frame is never worth crashing the camera over.
        } finally {
            image.close()
        }
    }
}
