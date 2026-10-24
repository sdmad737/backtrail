package com.backtrail.app.contracts

import java.io.File
import kotlinx.serialization.Serializable

data class ImageRef(val id: String, val absolutePath: String, val capturedAtMillis: Long?) {
    init {
        require(id.isNotBlank() && File(absolutePath).isAbsolute)
    }
}

data class RequestContext(
    val sessionId: String,
    val requestId: String,
    val tripId: String,
    val tripRevision: Long,
    val checkpointId: String,
) {
    init {
        require(listOf(sessionId, requestId, tripId, checkpointId).all { it.isNotBlank() })
        require(tripRevision >= 0)
    }
}

enum class FixQuality { UNAVAILABLE, APPROXIMATE, STALE, USABLE }

data class PositionFix(
    val latitude: Double,
    val longitude: Double,
    val elapsedRealtimeNanos: Long,
    val wallTimeMillis: Long,
    val accuracyMeters: Float,
)

enum class Maneuver { LEFT, RIGHT, STRAIGHT }

data class Checkpoint(
    val id: String,
    val ordinal: Int,
    val image: ImageRef,
    val label: String,
    val fixedFeatures: List<String>,
    val position: PositionFix? = null,
    val trackIndex: Int? = null,
    val userConfirmedManeuver: Maneuver? = null,
    val userNotes: String = "",
)

data class Trip(
    val id: String,
    val revision: Long,
    val startCheckpointId: String,
    val orderedCheckpoints: List<Checkpoint>,
    val track: List<PositionFix>,
    val endpointMarked: Boolean,
)

@Serializable
data class CheckpointDescription(val label: String, val fixedFeatures: List<String>)

@Serializable
enum class Verdict { SUPPORTS_MATCH, DIFFERENT_PLACE, INSUFFICIENT_EVIDENCE }

@Serializable
data class VisionAssessment(
    val verdict: Verdict,
    val matchingFeatures: List<String>,
    val conflictingFeatures: List<String>,
)

sealed interface DestinationProposal {
    data class Target(val checkpointId: String) : DestinationProposal
    data object AskClarification : DestinationProposal
    data object NoRecordedMatch : DestinationProposal
}

data class ReturnReminderProposal(val checkpointId: String, val userIntent: String)

enum class FailureKind { INVALID_RESULT, TIMEOUT, CANCELLED, UNAVAILABLE, ERROR }

sealed interface InferenceState<out T> {
    val context: RequestContext
    data class Loading(override val context: RequestContext) : InferenceState<Nothing>
    data class Success<T>(override val context: RequestContext, val value: T) : InferenceState<T>
    data class Failure(
        override val context: RequestContext,
        val kind: FailureKind,
        val message: String,
    ) : InferenceState<Nothing>
}
