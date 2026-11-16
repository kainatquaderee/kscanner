package com.kscanner.core.imaging

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance case P0.1 was written around: a still scene, and the two hypotheses the detector
 * keeps trading places between, played through the tracker for eight seconds of preview.
 *
 * Playback rather than re-detection. The defect is in what the tracker does with a *sequence* of
 * answers, and a still synthetic scene would not produce the sequence — it would produce the same
 * answer frame after frame. What is played back below is that sequence as it was measured in the
 * 149-frame recording, reduced to the shape a test can hold: both hypotheses offered every frame
 * with their ranking swapping, a frame with no outline at all now and then, and an unsure frame
 * whose guess is off the page.
 *
 * The two budgets are the plan's own, and they are screen pixels: outline edge motion of at most
 * 2 px per frame, and the outline present in at least 95 % of frames. The screen they were
 * measured on is 1080x2400, which is the view modelled here, so they can be compared with the
 * numbers below without any rescaling. The one figure that is *not* screen pixels is the frame
 * itself: the analyser hands the detector a 480x640 luma plane, and the preview fills the view
 * with it at `max(1080/480, 2400/640) = 3.75`, cropping what does not fit.
 */
class OutlineStabilityTest {

    private val frame = PreviewFrame(width = 480, height = 640, rotationDegrees = 0)
    private val viewWidth = 1080
    private val viewHeight = 2400

    /** What the preview does to a frame pixel: one of them covers 3.75 of the screen. */
    private val scale = 3.75

    /**
     * The sheet, in frame pixels and standing still, as the user's hands were. The preview's fill
     * keeps only the middle 288 of the frame's 480 columns (x from 96 to 384), which is where the
     * sheet has to sit to be on screen at all.
     */
    private val sheet = Quad(Pt(110.0, 128.0), Pt(370.0, 128.0), Pt(370.0, 512.0), Pt(110.0, 512.0))

    /**
     * The rival hypothesis measured in the recording, and the reason the stroke vanished: a quad
     * pinned to the frame's own border. Mapped onto a view that crops the frame's sides, its left
     * corners land 360 px outside the screen, so a border drawn on it is a border nobody can see.
     */
    private val frameBorder = Quad(Pt(0.0, 0.0), Pt(384.0, 0.0), Pt(384.0, 639.0), Pt(0.0, 639.0))

    /** The same page, guessed 58 screen pixels off while the detector was unsure of itself. */
    private val sheetUnsure = sheet.translated(-12.0, -10.0)

    /**
     * 5 dp, which is what [com.kscanner.ui.camera.CameraScreen] builds, on a 1080-pixel-wide screen
     * of the measured class: density 2.75, so 13.75 view px.
     */
    private val deadbandPx = 13.75

    /** Detection is asked for every 100 ms, as the tracker's own default paces it. */
    private val intervalMillis = OutlineTracker.DEFAULT_INTERVAL_MILLIS

    /** Sampled at about 40 fps, the rate the recording was measured at. */
    private val sampleStepMillis = 25L

    private val durationMillis = 8_000L

    private fun detection(quad: Quad, confidence: Double) = Detection(quad, confidence, DetectMethod.EDGES)

    /**
     * One detection's worth of answers, in the order the detector ranks them. Every case below was
     * seen in the recording; the indices only spread them so that no two land on the same frame.
     */
    private fun answers(index: Int): List<Detection> = when {
        // The outline has to start somewhere, and in the recording the sheet is what it started on.
        index == 0 -> listOf(detection(sheet, 0.93))

        // Frames with nothing in them. In the recording these came in runs short enough that the
        // outline was never silent for the whole hold: two frames here is a 300 ms gap between
        // accepted detections, against 400 ms of hold.
        index % 11 in 5..6 -> emptyList()

        // Only the rival survived: a page is in view, and where it is, is not news.
        index % 13 == 7 -> listOf(detection(frameBorder, 0.91))

        // Unsure, and wrong: above the evidence floor, below the confidence needed to draw it.
        index % 7 == 3 -> listOf(detection(sheetUnsure, 0.40))

        // Both descriptions of the same still page, and the ranking swapping between them. This is
        // the whole defect: a caller that takes the first answer is dragged across the frame while
        // nothing in front of the camera moves.
        index % 3 == 0 -> listOf(detection(frameBorder, 0.92), detection(sheet, 0.88))

        else -> listOf(detection(sheet, 0.93), detection(frameBorder, 0.86))
    }

    /** A stroke drawn off the view is, to the user, no stroke: this is what "present" means. */
    private fun visible(quad: Quad?): Boolean =
        quad != null && quad.points.all { p ->
            p.x >= 0.0 && p.x <= viewWidth.toDouble() && p.y >= 0.0 && p.y <= viewHeight.toDouble()
        }

    @Test
    fun `the outline holds its place through the measured sequence`() {
        val tracker = OutlineTracker(deadbandPx = deadbandPx)
        val positions = ArrayList<Quad?>()
        var detections = 0
        var rivalFirst = 0
        var silent = 0

        var now = 0L
        while (now <= durationMillis) {
            if (tracker.dueForDetection(now)) {
                val candidates = answers(detections)
                if (candidates.isEmpty()) silent++
                if (candidates.firstOrNull()?.quad == frameBorder) rivalFirst++
                tracker.accept(candidates, frame, viewWidth, viewHeight, now)
                detections++
            }
            positions.add(tracker.sample(now))
            now += sampleStepMillis
        }

        // A playback that stopped exercising the swap would keep passing forever, so check it did.
        assertTrue("only $detections detections were played", detections >= 75)
        assertTrue("the rival hypothesis was never offered first", rivalFirst >= 10)
        assertTrue("the scene never went quiet", silent >= 2)

        val present = positions.count { visible(it) }
        val ratio = present.toDouble() / positions.size
        assertTrue(
            "the outline was on screen in $present of ${positions.size} samples ($ratio)",
            ratio >= 0.95,
        )

        var worstStep = 0.0
        var worstAt = 0
        for (i in 1 until positions.size) {
            val before = positions[i - 1] ?: continue
            val after = positions[i] ?: continue
            val step = before.cornerDistance(after)
            if (step > worstStep) {
                worstStep = step
                worstAt = i
            }
        }
        assertTrue(
            "an edge moved $worstStep px between two samples, ${sampleStepMillis}ms apart (sample $worstAt)",
            worstStep <= 2.0,
        )

        // And it held the sheet rather than either rival description of it. The page is still, so
        // every sample should be the sheet exactly; the deadband is slack for arithmetic, not a
        // budget for drift.
        val expected = FrameGeometry.toView(sheet, frame, viewWidth, viewHeight)
        for ((i, quad) in positions.withIndex()) {
            if (quad == null) continue
            val off = quad.cornerDistance(expected)
            assertTrue("sample $i was ${off}px off the sheet: $quad", off <= deadbandPx)
        }
    }

    @Test
    fun `a page carried across the preview is followed, not frozen`() {
        // The risk the continuity rule introduces is the opposite failure: a page the user really
        // is carrying reads as a *different* page and the border freezes. Fifteen frame pixels per
        // detection is about 6 cm/s across a 25 cm field of view, which is a brisk carry, and well
        // inside the tolerance: 15 frame px is 56 screen px against a 250 px allowance.
        val perIntervalPx = 15.0
        val perMillisPx = perIntervalPx * scale / intervalMillis
        val tracker = OutlineTracker(deadbandPx = deadbandPx)
        val positions = ArrayList<Quad?>()

        fun pageAt(now: Long): Quad = sheet.translated(perIntervalPx / intervalMillis * now, 0.0)

        var now = 0L
        while (now <= 800L) {
            if (tracker.dueForDetection(now)) {
                tracker.accept(
                    listOf(detection(pageAt(now), 0.93)),
                    frame,
                    viewWidth,
                    viewHeight,
                    now,
                )
            }
            positions.add(tracker.sample(now))
            now += sampleStepMillis
        }

        // How far behind the page the outline is allowed to be: a full tween's worth of the page's
        // own travel, plus one deadband, which is the furthest the target can be from the outline
        // before a move starts at all.
        val maxLag = perMillisPx * OutlineTracker.DEFAULT_TWEEN_MILLIS + deadbandPx
        var worstLag = 0.0
        for ((i, quad) in positions.withIndex()) {
            if (quad == null) throw AssertionError("the outline disappeared at sample $i")
            val page = FrameGeometry.toView(pageAt(i * sampleStepMillis), frame, viewWidth, viewHeight)
            worstLag = maxOf(worstLag, quad.cornerDistance(page))
        }
        assertTrue("the outline fell $worstLag px behind the page, allowance $maxLag", worstLag <= maxLag)

        var worstStep = 0.0
        for (i in 1 until positions.size) {
            val before = positions[i - 1] ?: continue
            val after = positions[i] ?: continue
            worstStep = maxOf(worstStep, before.cornerDistance(after))
        }
        // The outline's speed should be the page's: it glides after it rather than overtaking it.
        val pageStep = perMillisPx * sampleStepMillis
        assertTrue(
            "an edge moved $worstStep px in one step, the page's own $pageStep px",
            worstStep <= pageStep + 1.0,
        )
    }
}