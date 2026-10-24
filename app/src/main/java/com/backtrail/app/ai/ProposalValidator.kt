package com.backtrail.app.ai

import com.backtrail.app.contracts.CheckpointDescription
import com.backtrail.app.contracts.DestinationProposal
import com.backtrail.app.contracts.VisionAssessment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Strict JSON: no code fences, unknown keys, invented actions or unrecorded checkpoint IDs. */
object ProposalValidator {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    fun description(raw: String): CheckpointDescription = decode<CheckpointDescription>(raw).also {
        require(it.label.isNotBlank() && it.label.length <= 100)
        features(it.fixedFeatures)
    }

    fun assessment(raw: String): VisionAssessment = decode<VisionAssessment>(raw).also {
        features(it.matchingFeatures)
        features(it.conflictingFeatures)
    }

    fun destination(raw: String, allowedIds: Set<String>): DestinationProposal {
        val proposal = decode<DestinationJson>(raw)
        return when (proposal.action) {
            "PROPOSE_TARGET" -> {
                require(proposal.checkpointId != null && proposal.checkpointId in allowedIds)
                DestinationProposal.Target(proposal.checkpointId)
            }
            "ASK_CLARIFICATION" -> {
                require(proposal.checkpointId == null)
                DestinationProposal.AskClarification
            }
            "NO_RECORDED_MATCH" -> {
                require(proposal.checkpointId == null)
                DestinationProposal.NoRecordedMatch
            }
            else -> throw IllegalArgumentException("Unsupported action")
        }
    }

    /** Gemma supplies every supported record; code owns the zero/one/many result state. */
    fun destinationMatches(raw: String, allowedIds: Set<String>): DestinationProposal {
        val ids = decode<DestinationMatchesJson>(raw).checkpointIds
        require(ids.size <= allowedIds.size && ids.all { it in allowedIds })
        require(ids.distinct().size == ids.size)
        return when (ids.size) {
            0 -> DestinationProposal.NoRecordedMatch
            1 -> DestinationProposal.Target(ids.single())
            else -> DestinationProposal.AskClarification
        }
    }

    private inline fun <reified T> decode(raw: String): T {
        require(raw.length in 2..8_192)
        return json.decodeFromString<T>(raw)
    }

    private fun features(values: List<String>) {
        require(values.size <= 8 && values.all { it.isNotBlank() && it.length <= 200 })
    }

    @Serializable
    private data class DestinationJson(val action: String, val checkpointId: String? = null)

    @Serializable
    private data class DestinationMatchesJson(val checkpointIds: List<String>)
}
