package com.backtrail.app.ai

/**
 * Deterministic lexical gate that runs *before* the model.
 *
 * A query sharing no word with any saved memory returns no candidates, and the caller then
 * answers `NO_RECORDED_MATCH` without spending an inference. That makes "I have no record of
 * that" a guarantee of ordinary code rather than a behaviour we hope the model exhibits —
 * models strongly prefer to pick something. It is also unit-testable on a host with no phone.
 *
 * Deliberate tradeoff: a paraphrase sharing no content word ("coat" for "jacket") is rejected
 * too. [SYNONYM_GROUPS] covers the demo vocabulary, and the gate is disclosed rather than
 * presented as model reasoning.
 */
object QueryPrefilter {

    /** Highest-overlap memories first, empty when nothing matches at all. */
    fun candidates(
        query: String,
        catalog: CheckpointCatalog,
        limit: Int = DEFAULT_LIMIT,
    ): List<CheckpointCatalog.Entry> {
        val queryTokens = expand(tokenize(query))
        if (queryTokens.isEmpty()) return emptyList()
        return catalog.entries
            .map { entry -> entry to overlap(queryTokens, expand(tokenize(entry.searchableText))) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (entry, _) -> entry }
    }

    fun tokenize(text: String): Set<String> = text.lowercase()
        .split(NON_WORD)
        .filterTo(mutableSetOf()) { it.length >= MIN_TOKEN_LENGTH && it !in STOP_WORDS }

    /** Maps each token to its synonym group so matching is symmetric in both directions. */
    private fun expand(tokens: Set<String>): Set<String> =
        tokens.flatMapTo(mutableSetOf()) { token -> SYNONYMS[token] ?: setOf(token) }

    private fun overlap(query: Set<String>, candidate: Set<String>): Int =
        query.count { it in candidate }

    const val DEFAULT_LIMIT = 4
    private const val MIN_TOKEN_LENGTH = 3
    private val NON_WORD = Regex("[^a-z0-9]+")

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "where", "did", "was", "were", "that", "this", "there",
        "here", "what", "which", "who", "how", "you", "your", "our", "its", "their", "from",
        "into", "onto", "are", "has", "had", "have", "can", "could", "would", "should", "will",
        "about", "near", "when", "any", "all", "but", "not", "put", "get", "got",
    )

    /**
     * Symmetric synonym groups for the demo vocabulary. Small and hand-written on purpose:
     * an embedding model is explicitly out of scope today.
     */
    private val SYNONYM_GROUPS = listOf(
        setOf("jacket", "coat", "anorak"),
        setOf("bench", "seat"),
        setOf("bridge", "crossing"),
        setOf("tree", "trunk", "log", "stump"),
        setOf("fork", "junction", "split"),
        setOf("gate", "stile"),
        setOf("car", "vehicle", "parking"),
        setOf("sign", "signpost", "marker"),
        setOf("bag", "backpack", "rucksack"),
        setOf("bottle", "flask"),
        setOf("left", "leave", "dropped", "forgot"),
    )

    private val SYNONYMS: Map<String, Set<String>> =
        SYNONYM_GROUPS.flatMap { group -> group.map { token -> token to group } }.toMap()
}
