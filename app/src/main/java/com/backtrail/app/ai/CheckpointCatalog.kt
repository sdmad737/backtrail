package com.backtrail.app.ai

import com.backtrail.app.contracts.Checkpoint

/**
 * The saved memories as the model is allowed to see them, plus the mapping back.
 *
 * Real checkpoint ids are UUID-shaped: costly in tokens and easily mangled in generated
 * output. The model only ever sees `c1..cN`, and [realIdFor] translates its answer back to a
 * real id before `ProposalValidator.destination` checks it against the allowed set. An id the
 * model invents therefore cannot survive translation.
 */
class CheckpointCatalog private constructor(val entries: List<Entry>) {

    data class Entry(val syntheticId: String, val checkpoint: Checkpoint) {
        /**
         * Label and notes are indexed alongside the visual features on purpose. A question
         * like "where did I leave my jacket?" will never match the fixed features of a trail
         * junction — only the note the user attached to it.
         */
        val searchableText: String = buildString {
            append(checkpoint.label)
            checkpoint.fixedFeatures.forEach { append(' ').append(it) }
            if (checkpoint.userNotes.isNotBlank()) append(' ').append(checkpoint.userNotes)
        }
    }

    val syntheticIds: Set<String> get() = entries.mapTo(mutableSetOf()) { it.syntheticId }

    fun realIdFor(syntheticId: String): String? =
        entries.firstOrNull { it.syntheticId == syntheticId }?.checkpoint?.id

    /** One line per memory. Everything here is text the app supplied, never model output. */
    fun promptLines(entries: List<Entry> = this.entries): String = entries.joinToString("\n") { entry ->
        val features = entry.checkpoint.fixedFeatures.joinToString("; ").ifBlank { "none recorded" }
        val note = entry.checkpoint.userNotes.ifBlank { "none" }
        "${entry.syntheticId}: label=${entry.checkpoint.label} | features=$features | note=$note"
    }

    companion object {
        fun of(checkpoints: List<Checkpoint>): CheckpointCatalog =
            CheckpointCatalog(checkpoints.mapIndexed { index, checkpoint -> Entry("c${index + 1}", checkpoint) })
    }
}
