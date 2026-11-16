package com.kscanner.core.imaging

/**
 * Picks which of a frame's candidate outlines the live preview should believe.
 *
 * A detector that returns one answer per frame has to decide the whole question again every
 * time, and the answer it prefers can change while nothing in front of the camera moved: two
 * hypotheses of the same page — a query around the ink and a looser one around the sheet, say —
 * trade places as the score of each drifts, and following whichever came top drags the outline
 * across the frame. What is missing is the frame before this one.
 *
 * So the chooser is given the outline already on screen, and prefers a candidate that *continues*
 * it. A candidate that does not continue it is not thrown away: it is reported as
 * [Choice.Disagree], because a page being there is worth knowing even when where it is, is not.
 *
 * Continuity is a distance measured against the size of the outline being followed, not against
 * pixels. A page does not cross its own width in a tenth of a second, so a candidate within a
 * small fraction of the shown outline's own span is the same page seen again; a candidate a third
 * of a frame away is a different guess, and moving there is a jump no tween should paper over.
 * Normalising by the outline rather than by the frame keeps that true whether the page fills the
 * preview or sits small in the middle of it.
 */
object OutlineChooser {

    /** What the chooser has to say about one frame's candidates. */
    sealed interface Choice {
        /**
         * A candidate that continues the outline on screen, or — when there is no outline yet —
         * the best-supported one. This is the outline to move to.
         */
        data class Follow(val detection: Detection) : Choice

        /**
         * Something that looks like a page, but nothing that agrees with the outline on screen.
         * The caller learns that a page is still in view; it learns nothing about where.
         */
        data class Disagree(val detection: Detection) : Choice

        /** Nothing in this frame is worth calling a page. */
        data object Nothing : Choice
    }

    /**
     * The lowest confidence that still counts as a page being in view. A candidate below it has
     * lost either the perimeter support or the interior contrast that a page has, so treating one
     * as news would keep a border alive over a page that has gone.
     */
    const val DEFAULT_FLOOR: Double = 0.30

    /**
     * The furthest a candidate may sit from the shown outline and still be that same page, as a
     * fraction of the shown outline's own span. Fifteen per cent covers a page being carried
     * across the preview at a brisk hand speed between two detections, and is a third of the gap
     * that opening a different hypothesis leaves behind.
     */
    const val DEFAULT_CONTINUITY: Double = 0.15

    /**
     * An outline whose mean diagonal is under this many pixels is a line rather than a page, and
     * no distance to it is worth judging.
     */
    private const val MIN_SPAN: Double = 1.0

    /**
     * The best answer for this frame given [shown], the outline currently being followed in the
     * same coordinates as [candidates] (so view pixels for the preview), or null when there is
     * none yet.
     */
    fun choose(
        candidates: List<Detection>,
        shown: Quad?,
        floor: Double = DEFAULT_FLOOR,
        continuity: Double = DEFAULT_CONTINUITY,
    ): Choice {
        val plausible = candidates.filter { it.confidence >= floor }
        if (plausible.isEmpty()) return Choice.Nothing

        // Nothing on screen yet: there is no continuity to judge, so the best evidence starts the
        // outline. The same is true of an outline with no size left — a page collapsed to a line
        // cannot say how far is far, so it must not be allowed to freeze the border.
        val judge = shown?.takeIf { canJudge(it) } ?: return Choice.Follow(best(plausible))

        val near = plausible.filter { continues(judge, it.quad, continuity) }
        if (near.isNotEmpty()) return Choice.Follow(best(near))
        return Choice.Disagree(best(plausible))
    }

    /**
     * Whether [candidate] is the same outline as [shown], seen again: within [continuity] of
     * [shown]'s own span. An outline with no span left cannot say how far is far, so nothing is
     * judged against it and everything is allowed to continue it.
     */
    fun continues(shown: Quad, candidate: Quad, continuity: Double = DEFAULT_CONTINUITY): Boolean {
        if (!canJudge(shown)) return true
        return candidate.cornerDistance(shown) <= spanOf(shown) * continuity
    }

    /**
     * Whether [shown] is an outline far enough from being a line for distances to it to mean
     * anything. Below [MIN_SPAN] a handful of pixels is most of its own size, so every candidate
     * would look like a different page and the border would freeze for want of a judge.
     */
    private fun canJudge(shown: Quad?): Boolean =
        shown != null && spanOf(shown) >= MIN_SPAN

    /** Highest confidence wins; ties keep the caller's order, which is the detector's ranking. */
    private fun best(candidates: List<Detection>): Detection =
        candidates.reduce { a, b -> if (b.confidence > a.confidence) b else a }

    /** Mean of the two diagonals: one number for how big the page on screen is. */
    private fun spanOf(quad: Quad): Double =
        (quad.tl.dist(quad.br) + quad.tr.dist(quad.bl)) / 2.0
}