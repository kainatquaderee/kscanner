package com.kscanner.core.imaging

/**
 * How far the page is turned for display: the orientation decision the detector made on the
 * untouched capture, and the quarter turns the user applied by hand, held apart.
 *
 * They are separate because they are re-decided at different moments. Detection runs again and
 * again over the same original, and has to stay free to revise its own answer; the turns the user
 * made are theirs, and no automatic pass may reverse them. Keeping one number would mean every
 * pass either inherits a stale decision or overwrites the user's work — which is exactly what
 * made Detect appear to turn the page back. [degrees] is the sum, and the only value the pixel
 * path reads, so rotation, crop, skew measurement and export all see the frame on screen.
 *
 * Both counts are quarter turns in 0..3, so a counter-clockwise turn is simply three clockwise
 * ones and the sum never has to be normalised twice.
 */
data class PageTurn(
    /** Clockwise quarter turns that make the capture upright, as decided on the original. */
    val planQuarterTurns: Int = 0,
    /** Further clockwise quarter turns the user applied to the frame the plan produced. */
    val userQuarterTurns: Int = 0,
) {

    init {
        require(planQuarterTurns in 0..3) { "plan turn must be 0..3 quarter turns, was $planQuarterTurns" }
        require(userQuarterTurns in 0..3) { "user turn must be 0..3 quarter turns, was $userQuarterTurns" }
    }

    /** Clockwise rotation of the capture to apply, in degrees. */
    val degrees: Int get() = ((planQuarterTurns + userQuarterTurns) % 4) * 90

    /** Takes a new decision from the detector, leaving the user's own turns untouched. */
    fun withPlan(quarterTurns: Int): PageTurn = copy(planQuarterTurns = wrap(quarterTurns))

    /** Turns the page [turns] quarter turns further clockwise; a negative count turns it back. */
    fun turnedBy(turns: Int): PageTurn = copy(userQuarterTurns = wrap(userQuarterTurns + turns))

    /**
     * The plan's outline as it lies in the frame this turn displays.
     *
     * A plan states its outline in the frame its own rotation produced, so the user's turn has to
     * be applied to the outline as well: move only the rotation and the border describes a
     * different physical edge of the same page.
     */
    fun applyTo(planQuad: Quad): Quad = planQuad.rotatedQuarterTurns(userQuarterTurns)

    private companion object {
        fun wrap(quarterTurns: Int): Int = ((quarterTurns % 4) + 4) % 4
    }
}