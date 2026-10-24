package com.backtrail.app.ai

import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * EmbeddingGemma 2 (740M, text + image + audio) behind one long-lived engine.
 *
 * Text and photos land in one shared vector space, so a spoken or typed "where I parked"
 * can be compared with saved photos and with saved labels/notes alike. Vectors are
 * L2-normalized by the runtime, so a dot product is the cosine similarity.
 *
 * Templates follow the google/embeddinggemma-2 model card: queries carry a task prefix,
 * documents a title/text frame, media no prefix at all.
 */
class LocalEmbedder(
    private val model: File,
    private val cache: File,
    // TENSOR is rejected by the 0.18.0 embedding engine ("Unsupported encoder backend: 5"); NPU works.
    private val backend: InferenceBackend = InferenceBackend.NPU,
    private val visionBackend: InferenceBackend = backend,
    private val nativeLibraryDir: String? = null,
    private val dimensions: Int? = DIMENSIONS,
) : AutoCloseable {
    private val mutex = Mutex()
    private var engine: EmbeddingEngine? = null

    /** Milliseconds spent in the most recent initialization, or -1 if none happened yet. */
    @Volatile var lastInitMs: Long = -1
        private set

    suspend fun embedQuery(query: String): FloatArray = embed(listOf(InputData.Text(queryText(query))))

    suspend fun embedDocument(title: String?, text: String): FloatArray =
        embed(listOf(InputData.Text(documentText(title, text))))

    /** [jpeg] is an encoded image; the runtime scales and patchifies it. */
    suspend fun embedImage(jpeg: ByteArray): FloatArray = embed(listOf(InputData.Image(jpeg)))

    private suspend fun embed(input: List<InputData>): FloatArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            engineLocked().computeEmbedding(input, OPTIONS_FOR[dimensions] ?: options(dimensions)).embedding
        }
    }

    private fun engineLocked(): EmbeddingEngine {
        engine?.let { return it }
        val created = EmbeddingEngine(
            EmbeddingEngineConfig(
                modelPath = model.absolutePath,
                backend = backend.toRuntime(nativeLibraryDir),
                visionBackend = visionBackend.toRuntime(nativeLibraryDir),
                cacheDir = cache.absolutePath,
            ),
        )
        val start = System.nanoTime()
        try {
            created.initialize()
        } catch (failure: Throwable) {
            runCatching { created.close() }
            throw failure
        }
        lastInitMs = (System.nanoTime() - start) / 1_000_000
        engine = created
        return created
    }

    override fun close() {
        // Best effort without suspending: an in-flight embed finishes on its own engine reference.
        engine?.close()
        engine = null
    }

    companion object {
        /** The model card reports quality close to 768d down to 256d; 128d hurts images badly. */
        const val DIMENSIONS = 256

        fun queryText(query: String) = "task: search result | query: ${query.trim()}"

        fun documentText(title: String?, text: String) =
            "title: ${title?.trim()?.ifBlank { null } ?: "none"} | text: ${text.trim()}"

        private fun options(dimensions: Int?) = EmbeddingOptions(normalize = true, outputSize = dimensions)
        private val OPTIONS_FOR = mapOf<Int?, EmbeddingOptions>(DIMENSIONS to options(DIMENSIONS))
    }
}
