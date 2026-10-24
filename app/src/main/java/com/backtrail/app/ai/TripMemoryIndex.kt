package com.backtrail.app.ai

import com.backtrail.app.contracts.Memory
import java.io.File

/**
 * EmbeddingGemma index over the trip's saved memories (photo + title/description/note).
 *
 * Vectors are computed once per memory and cached by content, so asking ten questions costs
 * ten query embeddings, not ten re-indexings. [rank] narrows a whole trip to the few memories
 * worth showing Gemma; the final answer, and any "no match", still comes from Gemma plus
 * validation, because embedding scores alone cannot tell "nothing fits" (INFERENCE-LOG.md).
 */
class TripMemoryIndex(private val embedder: LocalEmbedder) {
    private val cache = HashMap<String, SpotRetriever.SpotVectors>()

    data class Ranked(val memory: Memory, val score: Float)

    /** Memories ranked by similarity to [query], limited to the last [withinHours] when given. */
    suspend fun rank(
        query: String,
        memories: List<Memory>,
        nowMillis: Long = System.currentTimeMillis(),
        withinHours: Double? = null,
        limit: Int = 3,
    ): List<Ranked> {
        val pool = recent(memories, nowMillis, withinHours)
        if (pool.isEmpty()) return emptyList()
        val queryVector = embedder.embedQuery(query)
        val vectors = pool.map { vectorsFor(it) }
        val scores = SpotRetriever().rank(queryVector, vectors).associateBy { it.spotId }
        return pool.map { Ranked(it, scores.getValue(it.id).score) }.sortedByDescending { it.score }.take(limit)
    }

    /** Embeds anything not yet indexed. Call after a photo is saved so questions start warm. */
    suspend fun index(memories: List<Memory>) {
        memories.forEach { vectorsFor(it) }
    }

    private suspend fun vectorsFor(memory: Memory): SpotRetriever.SpotVectors {
        val key = cacheKey(memory)
        cache[key]?.let { return it }
        val text = documentText(memory)
        val photo = File(memory.photoPath).takeIf { memory.photoPath.isNotBlank() && it.isFile }
        val vectors = SpotRetriever.SpotVectors(
            spotId = memory.id,
            image = photo?.let { runCatching { embedder.embedImage(it.readBytes()) }.getOrNull() },
            text = text.takeIf { it.isNotBlank() }?.let { embedder.embedDocument(memory.title, it) },
        )
        cache[key] = vectors
        return vectors
    }

    companion object {
        fun recent(memories: List<Memory>, nowMillis: Long, withinHours: Double?): List<Memory> {
            if (withinHours == null) return memories
            require(withinHours > 0)
            val cutoff = nowMillis - (withinHours * 3_600_000).toLong()
            return memories.filter { it.capturedAt in cutoff..nowMillis }
        }

        fun documentText(memory: Memory): String = listOf(memory.description, memory.note)
            .filter { it.isNotBlank() }
            .joinToString(". ")

        private fun cacheKey(memory: Memory) =
            "${memory.id}|${memory.photoPath}|${(memory.title + documentText(memory)).hashCode()}"
    }
}
