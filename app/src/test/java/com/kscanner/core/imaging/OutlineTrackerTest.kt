package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live outline's memory, checked against a clock the test owns: no sleeping, no camera.
 *
 * The geometry is deliberately trivial: a 4x6 frame in a 100x100 view scales by 25 and is
 * centred, so a detected corner at (1, 2) is drawn at (25, 25).
 */
class OutlineTrackerTest {

    private val frame = PreviewFrame(width = 4, height = 6, rotationDegrees = 0)

    /** A page filling the middle of the frame. */
    private val found = Quad(
        Pt(1.0, 2.0),
        Pt(2.0, 2.0),
        Pt(2.0, 4.0),
        Pt(1.0, 4.0),
    )

    /** The same page, one frame pixel to the right: the camera moved. */
    private val moved = found.translated(1.0, 0.0)

    private fun detection(quad: Quad, confidence: Double = 0.9) =
        Detection(quad, confidence, DetectMethod.EDGES)

    private fun tracker(
        deadbandPx: Double = 5.0,
        minConfidence: Double = OutlineTracker.DEFAULT_MIN_CONFIDENCE,
        tweenMillis: Long = OutlineTracker.DEFAULT_TWEEN_MILLIS,
    ) = OutlineTracker(
        deadbandPx = deadbandPx,
        minConfidence = minConfidence,
        tweenMillis = tweenMillis,
        holdMillis = 400,
    )

    /** The corners a [Quad] in frame coordinates lands on in a 100x100 view. */
    private fun onScreen(quad: Quad): Quad = FrameGeometry.toView(quad, frame, 100, 100)

    private fun between(a: Quad, b: Quad, f: Double): Quad = Quad(
        Pt.lerp(a.tl, b.tl, f),
        Pt.lerp(a.tr, b.tr, f),
        Pt.lerp(a.br, b.br, f),
        Pt.lerp(a.bl, b.bl, f),
    )

    private fun assertQuad(expected: Quad, actual: Quad?, why: String = "") {
        assertEquals("$why corner tl", expected.tl, actual?.tl)
        assertEquals("$why corner tr", expected.tr, actual?.tr)
        assertEquals("$why corner br", expected.br, actual?.br)
        assertEquals("$why corner bl", expected.bl, actual?.bl)
    }

    @Test
    fun `nothing is drawn before the first detection`() {
        assertNull(tracker().sample(0))
    }

    @Test
    fun `the first outline appears where it was found`() {
        val tracker = tracker()
        tracker.accept(detection(found), frame, 100, 100, now = 1_000)

        assertQuad(onScreen(found), tracker.sample(1_000))
        assertQuad(onScreen(found), tracker.sample(1_040))
    }

    @Test
    fun `an outline nobody is sure about is not drawn`() {
        val tracker = tracker()
        tracker.accept(detection(found, confidence = 0.2), frame, 100, 100, now = 0)

        assertNull(tracker.sample(0))
    }

    @Test
    fun `an outline exactly at the confidence gate is drawn`() {
        val tracker = tracker(minConfidence = 0.45)
        tracker.accept(detection(found, confidence = 0.45), frame, 100, 100, now = 0)

        assertQuad(onScreen(found), tracker.sample(0))
    }

    @Test
    fun `detections are throttled to the requested interval`() {
        val tracker = tracker()
        val interval = OutlineTracker.DEFAULT_INTERVAL_MILLIS

        assertTrue("the first frame should be detected", tracker.dueForDetection(10_000))
        assertFalse("the next frame is too soon", tracker.dueForDetection(10_000 + interval / 3))
        assertFalse("still too soon", tracker.dueForDetection(10_000 + interval - 1))
        assertTrue("by now a detection is due", tracker.dueForDetection(10_000 + interval))
        assertTrue("and again a full interval later", tracker.dueForDetection(10_000 + interval * 2))
    }

    @Test
    fun `detections that only tremble are not followed`() {
        val tracker = tracker(deadbandPx = 5.0)
        tracker.accept(detection(found), frame, 100, 100, now = 0)
        // A tenth of a frame pixel is under half the deadband once the view scale is applied.
        tracker.accept(detection(found.translated(0.1, 0.1)), frame, 100, 100, now = 1_000)

        assertQuad(onScreen(found), tracker.sample(1_040))
        assertQuad(onScreen(found), tracker.sample(1_400))
    }

    @Test
    fun `a moved outline is tweened into place`() {
        val tracker = tracker(tweenMillis = 120)
        tracker.accept(detection(found), frame, 100, 100, now = 0)
        tracker.accept(detection(moved), frame, 100, 100, now = 1_000)

        assertQuad(onScreen(found), tracker.sample(1_000))
        assertQuad(between(onScreen(found), onScreen(moved), 0.5), tracker.sample(1_060))
        assertQuad(onScreen(moved), tracker.sample(1_120))
        assertQuad(onScreen(moved), tracker.sample(1_400), "where it stays")
    }

    @Test
    fun `a second move carries on from where the outline has got to`() {
        val tracker = tracker(tweenMillis = 120)
        tracker.accept(detection(found), frame, 100, 100, now = 0)
        tracker.accept(detection(moved), frame, 100, 100, now = 1_000)

        val quarter = between(onScreen(found), onScreen(moved), 0.25)
        assertQuad(quarter, tracker.sample(1_030))

        val further = moved.translated(1.0, 0.0)
        tracker.accept(detection(further), frame, 100, 100, now = 1_030)

        assertQuad(quarter, tracker.sample(1_030))
        assertQuad(between(quarter, onScreen(further), 0.5), tracker.sample(1_090))
        assertQuad(onScreen(further), tracker.sample(1_150))
    }

    @Test
    fun `a tween of no length jumps straight there`() {
        val tracker = tracker(tweenMillis = 0)
        tracker.accept(detection(found), frame, 100, 100, now = 0)
        tracker.accept(detection(moved), frame, 100, 100, now = 1_000)

        assertQuad(onScreen(moved), tracker.sample(1_000))
    }

    @Test
    fun `the outline goes once nothing trustworthy is detected`() {
        val tracker = tracker()
        tracker.accept(detection(found), frame, 100, 100, now = 0)
        tracker.accept(detection(found, confidence = 0.1), frame, 100, 100, now = 100)

        assertQuad(onScreen(found), tracker.sample(400))
        assertNull("the hold has run out", tracker.sample(401))
        assertNull(tracker.sample(9_000))
    }

    @Test
    fun `steady detections keep the outline up`() {
        val tracker = tracker()
        var now = 0L
        repeat(11) {
            tracker.accept(detection(found), frame, 100, 100, now)
            now += OutlineTracker.DEFAULT_INTERVAL_MILLIS
        }

        val lastSeen = now - OutlineTracker.DEFAULT_INTERVAL_MILLIS
        assertQuad(onScreen(found), tracker.sample(now))
        assertQuad(onScreen(found), tracker.sample(lastSeen + 400), "one frame of slack is not enough to lose it")
        assertNull("but the hold does end", tracker.sample(lastSeen + 401))
    }

    @Test
    fun `a view that has not been measured is not drawn into`() {
        val tracker = tracker()
        tracker.accept(detection(found), frame, viewWidth = 0, viewHeight = 0, now = 0)

        assertNull(tracker.sample(0))
    }

    @Test
    fun `the outline is drawn where the rotated frame puts it`() {
        // A quarter turn: the frame stands 6x4 in a 60x40 view, so the scale is exactly 10.
        val tracker = tracker()
        tracker.accept(
            detection(found),
            frame.copy(rotationDegrees = 90),
            viewWidth = 60,
            viewHeight = 40,
            now = 0,
        )

        assertQuad(
            Quad(Pt(10.0, 10.0), Pt(30.0, 10.0), Pt(30.0, 20.0), Pt(10.0, 20.0)),
            tracker.sample(0),
        )
    }
}
