package com.kscanner.core.imaging

/** How an outline was found; surfaced in the UI so the result can be explained. */
enum class DetectMethod { EDGES, BRIGHT_REGION, TEXT_BLOCK }

/** One candidate outline with the evidence that produced it. */
data class Detection(
    val quad: Quad,
    /** 0..1. */
    val confidence: Double,
    val method: DetectMethod,
    /** Raw ranking score, before it is squashed into [confidence]. */
    val score: Double = confidence,
)
