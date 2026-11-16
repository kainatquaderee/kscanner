package com.kscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnTest {

    private val outline = Quad(
        Pt(0.10, 0.20),
        Pt(0.80, 0.15),
        Pt(0.90, 0.70),
        Pt(0.05, 0.85),
    )

    @Test
    fun `the effective rotation sums the plan's decision and the user's turns`() {
        assertEquals(0, PageTurn().degrees)
        assertEquals(90, PageTurn(planQuarterTurns = 1).degrees)
        assertEquals(90, PageTurn(userQuarterTurns = 1).degrees)
        assertEquals(180, PageTurn(planQuarterTurns = 1, userQuarterTurns = 1).degrees)
        // Four quarter turns are none at all, wherever they came from.
        assertEquals(0, PageTurn(planQuarterTurns = 3, userQuarterTurns = 1).degrees)
        assertEquals(0, PageTurn(planQuarterTurns = 1, userQuarterTurns = 3).degrees)
    }

    @Test
    fun `detect replaces the plan's decision and leaves the user's turn in place`() {
        val turnedByHand = PageTurn(planQuarterTurns = 1).turnedBy(1)

        val redetected = turnedByHand.withPlan(0)

        assertEquals(1, redetected.userQuarterTurns)
        assertEquals(1, turnedByHand.withPlan(3).userQuarterTurns)
    }

    @Test
    fun `a counter clockwise turn is three clockwise ones`() {
        assertEquals(PageTurn().turnedBy(-1), PageTurn().turnedBy(3))
        assertEquals(PageTurn(userQuarterTurns = 1).turnedBy(-1), PageTurn())
        assertEquals(PageTurn().turnedBy(4), PageTurn())
    }

    @Test
    fun `the plan's outline moves by the user's turn alone`() {
        // The plan's own half has to stay out of this: the outline it reported is already expressed
        // in the frame its rotation made, so only the user's turn moves it on from there. Counting
        // either half twice is the same defect wearing the wrong border.
        for (user in 0..3) {
            val turn = PageTurn(planQuarterTurns = 2, userQuarterTurns = user)
            assertEquals(
                0.0,
                TestImages.maxCornerError(outline.rotatedQuarterTurns(user), turn.applyTo(outline)),
                1e-12,
            )
        }
    }

    @Test
    fun `a plan turn and a hand turn of the same size compose`() {
        // A plan that decided a quarter turn, and a user who turned one more: the capture is shown
        // half a turn round, and the plan's outline has to arrive there too.
        val fromPlan = outline.rotatedQuarterTurns(1)
        val displayed = PageTurn(planQuarterTurns = 1, userQuarterTurns = 1)

        assertEquals(180, displayed.degrees)
        assertEquals(0.0, TestImages.maxCornerError(outline.rotatedQuarterTurns(2), displayed.applyTo(fromPlan)), 1e-12)

        // Detect re-decides the orientation from the original and reports no turn at all. The user
        // still has their quarter turn, so the page stays turned rather than snapping back.
        val redetected = displayed.withPlan(0)
        assertEquals(90, redetected.degrees)
        assertTrue(redetected.applyTo(outline).isConvex())
        assertEquals(0.0, TestImages.maxCornerError(outline.rotatedQuarterTurns(1), redetected.applyTo(outline)), 1e-12)
    }
}