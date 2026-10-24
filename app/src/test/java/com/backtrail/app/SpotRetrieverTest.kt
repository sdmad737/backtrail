package com.backtrail.app

import com.backtrail.app.ai.AlignmentMeter
import com.backtrail.app.ai.SpotRetriever
import com.backtrail.app.ai.SpotRetriever.Decision
import com.backtrail.app.ai.SpotRetriever.SpotVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotRetrieverTest {
    private val retriever = SpotRetriever(SpotRetriever.Thresholds(floor = 0.3f, direct = 0.6f, margin = 0.1f))

    private fun v(vararg x: Float) = x

    @Test fun clearWinnerSkipsTheBigModel() {
        val spots = listOf(
            SpotVectors("car", image = v(1f, 0f, 0f), text = null),
            SpotVectors("bridge", image = v(0f, 1f, 0f), text = null),
        )
        val decision = retriever.decide(v(0.95f, 0.1f, 0f), spots)
        assertTrue(decision is Decision.Direct)
        assertEquals("car", (decision as Decision.Direct).best.spotId)
    }

    @Test fun closeScoresGoToTheModelWithOnlyTheCandidates() {
        val spots = listOf(
            SpotVectors("bench", image = null, text = v(1f, 1f, 0f)),
            SpotVectors("seat", image = null, text = v(1f, 0.9f, 0f)),
            SpotVectors("gate", image = null, text = v(0f, 0f, 1f)),
        )
        val decision = retriever.decide(v(1f, 1f, 0f), spots)
        assertTrue(decision is Decision.AskModel)
        assertEquals(setOf("bench", "seat"), (decision as Decision.AskModel).candidates.map { it.spotId }.toSet())
    }

    @Test fun nothingCloseIsDecidedByCodeNotTheModel() {
        val spots = listOf(SpotVectors("gate", image = v(0f, 0f, 1f), text = v(0f, 0.1f, 1f)))
        assertTrue(retriever.decide(v(1f, 0f, 0f), spots) is Decision.NoMatch)
        assertTrue(retriever.decide(v(1f, 0f, 0f), emptyList()) is Decision.NoMatch)
    }

    @Test fun aSpotScoresAsItsBestModality() {
        // The query matches the note text, not the photo: "where I left my jacket".
        val spots = listOf(SpotVectors("bench", image = v(0f, 1f, 0f), text = v(1f, 0f, 0f)))
        assertEquals(1f, retriever.rank(v(1f, 0f, 0f), spots).single().score, 1e-5f)
    }

    @Test fun cosineHandlesZeroAndUnnormalizedVectors() {
        assertEquals(0f, SpotRetriever.cosine(v(0f, 0f), v(1f, 0f)), 0f)
        assertEquals(1f, SpotRetriever.cosine(v(3f, 4f), v(6f, 8f)), 1e-6f)
    }

    @Test fun meterNeedsSustainedSimilarityAndDoesNotFlicker() {
        val meter = AlignmentMeter(closeAt = 0.5f, alignedAt = 0.7f, hysteresis = 0.03f, smoothing = 0.5f)
        assertEquals(AlignmentMeter.Level.SEARCHING, meter.update(0.2f))
        // One lucky frame is smoothed away.
        assertEquals(AlignmentMeter.Level.SEARCHING, meter.update(0.8f))
        repeat(4) { meter.update(0.8f) }
        assertEquals(AlignmentMeter.Level.ALIGNED, meter.level)
        // A dip just under the boundary stays ALIGNED thanks to hysteresis.
        assertEquals(AlignmentMeter.Level.ALIGNED, meter.update(0.68f))
        repeat(6) { meter.update(0.2f) }
        assertEquals(AlignmentMeter.Level.SEARCHING, meter.level)
    }
}
