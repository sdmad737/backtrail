package com.backtrail.app.ai

import com.backtrail.app.contracts.CheckpointDescription
import com.backtrail.app.contracts.Outing
import com.backtrail.app.contracts.RequestContext

/** Must run inside the outing store's mutation lock, after the UI request gate accepts. */
internal object DescriptionCommit {
    data class Decision(val outing: Outing, val accepted: Boolean)

    fun apply(outing: Outing, request: RequestContext, photoPath: String, description: CheckpointDescription): Decision {
        if (outing.id != request.tripId || outing.revision != request.tripRevision) return Decision(outing, false)
        val memory = outing.memories.firstOrNull { it.id == request.checkpointId && it.photoPath == photoPath }
            ?: return Decision(outing, false)
        val unclear = description.label.equals("Unclear view", ignoreCase = true) || description.fixedFeatures.isEmpty()
        val updated = memory.copy(
            description = if (unclear) "" else description.fixedFeatures.joinToString(" · "),
            title = if(memory.titleEdited) memory.title else PlaceNaming.afterDescription(memory.title,memory.note,description.label,unclear),
            indexing = "Gemma 4 · local result",
        )
        if (updated == memory) return Decision(outing, true)
        return Decision(outing.copy(
            revision = outing.revision + 1,
            memories = outing.memories.map { if (it.id == memory.id) updated else it },
        ), true)
    }
}
