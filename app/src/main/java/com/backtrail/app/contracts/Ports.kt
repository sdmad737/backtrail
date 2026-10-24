package com.backtrail.app.contracts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface VisionService {
    fun describe(context: RequestContext, image: ImageRef): Flow<InferenceState<CheckpointDescription>>
    fun compare(
        context: RequestContext,
        reference: ImageRef,
        current: ImageRef,
    ): Flow<InferenceState<VisionAssessment>>
    fun resolveDestination(
        context: RequestContext,
        query: String,
        allowedCheckpoints: List<Checkpoint>,
    ): Flow<InferenceState<DestinationProposal>>
}

interface TripRepository {
    suspend fun load(tripId: String): Trip?
    /** Metadata writes must be atomic. Images must already be app-owned. */
    suspend fun save(trip: Trip)
    suspend fun importImage(sourcePath: String, capturedAtMillis: Long): ImageRef
}

data class RecordingState(
    val recording: Boolean = false,
    val fix: PositionFix? = null,
    val quality: FixQuality = FixQuality.UNAVAILABLE,
    val failure: String? = null,
)

interface TrackRecorder {
    val state: StateFlow<RecordingState>
    suspend fun start()
    suspend fun stop()
}

enum class ReturnPhase { IDLE, FOLLOWING, ARRIVED }
data class ReturnState(
    val phase: ReturnPhase = ReturnPhase.IDLE,
    val sessionId: String? = null,
    val expectedCheckpointId: String? = null,
    val targetCheckpointId: String? = null,
    val canUndo: Boolean = false,
)

interface ReturnController {
    val state: StateFlow<ReturnState>
    /** Called only after the user confirms the destination and recorded endpoint. */
    fun begin(trip: Trip, targetCheckpointId: String)
    /** Issues a unique request context for the expected checkpoint. */
    fun newComparisonRequest(): RequestContext
    /** Explicit user action; consumes a fresh request at most once. Model callbacks never call this. */
    fun confirmExpected(context: RequestContext): Boolean
    /** Invalidates all pending work, including when no progress can be undone. */
    fun undo()
    fun end()
}
