package com.backtrail.app.ai

import com.backtrail.app.contracts.Memory
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Preserve exact chronology and original note boundaries, even for a relevance-sorted subset. */
internal object TripRecordPrompt {
    fun record(id: String, memory: Memory, nowMillis: Long, hasPhoto: Boolean): String = JsonObject(mapOf(
        "id" to JsonPrimitive(id),
        "capturedAtMillis" to JsonPrimitive(memory.capturedAt),
        "routeSampleIndex" to (memory.routeIndex?.let(::JsonPrimitive) ?: JsonNull),
        "minutesAgo" to JsonPrimitive(((nowMillis - memory.capturedAt) / 60_000).coerceAtLeast(0)),
        "title" to JsonPrimitive(memory.title.take(100)),
        "observations" to JsonPrimitive(memory.description.take(800)),
        "userNote" to JsonPrimitive(memory.note.take(800)),
        "photoFollows" to JsonPrimitive(hasPhoto),
    )).toString()
}
