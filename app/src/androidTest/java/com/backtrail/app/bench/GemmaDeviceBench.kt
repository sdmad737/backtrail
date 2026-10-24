package com.backtrail.app.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backtrail.app.ai.InferenceBackend
import com.backtrail.app.ai.InferenceTrace
import com.backtrail.app.ai.LocalEmbedder
import com.backtrail.app.ai.LocalVisionService
import com.backtrail.app.ai.SpotRetriever
import com.backtrail.app.contracts.ImageRef
import com.backtrail.app.contracts.InferenceState
import com.backtrail.app.contracts.RequestContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device measurements for the numbers we report. Nothing here is a pass/fail test of
 * quality: each run writes raw answers, timings and memory to files/bench/ for review.
 *
 * Inputs (copied in with `adb shell run-as`, never committed):
 *   no_backup/models/<model>.litertlm   files/bench-photos/<name>.jpg
 *
 * Run one configuration (see docs/INFERENCE-LOG.md for the exact commands):
 *   adb shell am instrument -w -e class com.backtrail.app.bench.GemmaDeviceBench#describe \
 *     -e backend GPU -e model gemma-4-E2B-it.litertlm -e tag gpu \
 *     com.backtrail.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class GemmaDeviceBench {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val modelsDir = File(context.noBackupFilesDir, "models")
    private val photosDir = File(context.filesDir, "bench-photos")
    private val outDir = File(context.filesDir, "bench").apply { mkdirs() }
    private val nativeLibraryDir = context.applicationInfo.nativeLibraryDir

    private fun arg(name: String, default: String) = args.getString(name) ?: default
    private fun photos() = (photosDir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") } ?: emptyArray()).sortedBy { it.name }

    @Test
    fun describe(): Unit = runBlocking {
        val model = File(modelsDir, arg("model", "gemma-4-E2B-it.litertlm"))
        val backend = InferenceBackend.valueOf(arg("backend", "GPU"))
        val vision = InferenceBackend.valueOf(arg("vision", backend.name))
        val promptName = arg("prompt", "v2")
        val prompt = if (promptName == "v1") LocalVisionService.DESCRIBE_PROMPT_V1 else LocalVisionService.DESCRIBE_PROMPT
        val runs = arg("runs", "2").toInt()
        val tag = arg("tag", "${backend.name.lowercase()}-$promptName")

        var lastRaw: String? = null
        var lastTrace: InferenceTrace? = null
        val rssBefore = rssMb()
        val service = LocalVisionService(
            model, context.cacheDir, backend, vision, nativeLibraryDir, prompt,
            rawOutputSink = { lastRaw = it },
            traceSink = { lastTrace = it },
        )
        val results = JSONArray()
        var peakRss = rssBefore
        try {
            for (photo in photos()) {
                repeat(runs) { run ->
                    lastRaw = null
                    lastTrace = null
                    val image = ImageRef(UUID.randomUUID().toString(), photo.absolutePath, null)
                    val request = RequestContext("bench", UUID.randomUUID().toString(), "bench", 0, photo.name)
                    val final = service.describe(request, image).toList().last()
                    peakRss = maxOf(peakRss, rssMb())
                    val t = lastTrace
                    results.put(JSONObject().apply {
                        put("photo", photo.name)
                        put("run", run + 1)
                        put("engine", t?.engine)
                        put("initMs", t?.initMs)
                        put("generateMs", t?.generateMs)
                        put("outcome", t?.outcome)
                        put("raw", lastRaw ?: JSONObject.NULL)
                        if (final is InferenceState.Success) {
                            put("label", final.value.label)
                            put("fixedFeatures", JSONArray(final.value.fixedFeatures))
                        }
                    })
                    Log.i(TAG, "describe tag=$tag photo=${photo.name} run=${run + 1} ${t?.line()}")
                }
            }
        } finally {
            service.close()
        }
        val report = JSONObject().apply {
            put("op", "describe")
            put("tag", tag)
            put("model", model.name)
            put("backend", backend.name)
            put("visionBackend", vision.name)
            put("prompt", promptName)
            put("rssMbBefore", rssBefore)
            put("rssMbPeak", peakRss)
            put("results", results)
        }
        File(outDir, "describe-$tag.json").writeText(report.toString(2))
        Log.i(TAG, "describe tag=$tag done rssMbPeak=$peakRss")
    }

    @Test
    fun embed(): Unit = runBlocking {
        val model = File(modelsDir, arg("model", "embeddinggemma-2-740m_Google_Tensor_G6.litertlm"))
        val backend = InferenceBackend.valueOf(arg("backend", "TENSOR"))
        val vision = InferenceBackend.valueOf(arg("vision", backend.name))
        val tag = arg("tag", "embed-${backend.name.lowercase()}")
        val rssBefore = rssMb()
        val embedder = LocalEmbedder(model, context.cacheDir, backend, vision, nativeLibraryDir)
        val report = JSONObject().put("op", "embed").put("tag", tag).put("model", model.name)
            .put("backend", backend.name).put("visionBackend", vision.name).put("rssMbBefore", rssBefore)
        try {
            // Warm-up call carries initialization; timed separately.
            val firstStart = System.nanoTime()
            embedder.embedQuery("warm up")
            report.put("firstCallMs", msSince(firstStart)).put("initMs", embedder.lastInitMs)

            val docVectors = DEMO_SPOTS.map { (id, title, text) ->
                val start = System.nanoTime()
                val vector = embedder.embedDocument(title, text)
                Triple(id, vector, msSince(start))
            }
            report.put("docMs", JSONArray(docVectors.map { it.third }))
            report.put("dimensions", docVectors.first().second.size)

            val spots = docVectors.map { SpotRetriever.SpotVectors(it.first, image = null, text = it.second) }
            val retriever = SpotRetriever()
            val queries = JSONArray()
            for ((query, expected) in DEMO_QUERIES) {
                val start = System.nanoTime()
                val vector = embedder.embedQuery(query)
                val ms = msSince(start)
                val ranked = retriever.rank(vector, spots)
                val decision = retriever.decide(vector, spots)
                queries.put(JSONObject().apply {
                    put("query", query)
                    put("expected", expected ?: JSONObject.NULL)
                    put("ms", ms)
                    put("ranked", JSONArray(ranked.map { JSONObject().put("id", it.spotId).put("score", it.score.toDouble()) }))
                    put("decision", decision.javaClass.simpleName)
                    put("top1Correct", expected != null && ranked.first().spotId == expected)
                })
            }
            report.put("queries", queries)

            val photoVectors = photos().map { photo ->
                val start = System.nanoTime()
                val vector = runCatching { embedder.embedImage(photo.readBytes()) }
                Triple(photo.name, vector, msSince(start))
            }
            report.put("images", JSONArray(photoVectors.map { (name, vector, ms) ->
                JSONObject().put("photo", name).put("ms", ms)
                    .put("error", vector.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: JSONObject.NULL)
            }))
            // Photo-vs-photo similarities: same-place pairs should score well above different places.
            val ok = photoVectors.filter { it.second.isSuccess }.map { it.first to it.second.getOrThrow() }
            val pairs = JSONArray()
            for (i in ok.indices) for (j in i + 1 until ok.size) {
                pairs.put(JSONObject().put("a", ok[i].first).put("b", ok[j].first)
                    .put("cosine", SpotRetriever.cosine(ok[i].second, ok[j].second).toDouble()))
            }
            report.put("imagePairs", pairs)
            // Cross-modal: each demo query against each photo.
            report.put("queryVsPhoto", JSONArray(ok.map { (name, vector) ->
                JSONObject().put("photo", name).put("scores", JSONObject().apply {
                    for ((query, _) in DEMO_QUERIES) put(query, SpotRetriever.cosine(vector, embedder.embedQuery(query)).toDouble())
                })
            }))
            report.put("rssMbAfter", rssMb())
        } catch (failure: Throwable) {
            report.put("error", "${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            embedder.close()
        }
        File(outDir, "embed-$tag.json").writeText(report.toString(2))
        Log.i(TAG, "embed tag=$tag done ${report.optString("error")}")
    }

    private fun msSince(start: Long) = (System.nanoTime() - start) / 1_000_000

    /** Resident memory of this process (app + instrumentation share it), in MB. */
    private fun rssMb(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmRSS:") }
        ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.div(1024) ?: -1

    private companion object {
        const val TAG = "TravisBench"

        /** Synthetic demo trip, matching the UX mockup. Not user data. */
        val DEMO_SPOTS = listOf(
            Triple("car", "Trailhead car park", "Gravel car park; brown trailhead sign; wooden fence; start of the walk"),
            Triple("bridge", "Wooden footbridge", "Narrow wooden footbridge over a stream; handrails; ferns on both banks"),
            Triple("mailbox", "Red mailbox by stone wall", "Red mailbox on a post; low stone wall; path forks left. Note: pick up honey from the farm stand here on the way back"),
            Triple("bench", "Bench at the overlook", "Green bench facing the valley; information board. Note: I left my jacket on this bench"),
        )

        /** Query -> expected spot id, or null when no saved spot should match. */
        val DEMO_QUERIES = listOf(
            "back to where I parked" to "car",
            "take me to the car" to "car",
            "the little bridge over the creek" to "bridge",
            "where did I leave my coat" to "bench",
            "the place with the honey" to "mailbox",
            "the fork with the red letterbox" to "mailbox",
            "the viewpoint" to "bench",
            "the helicopter pad" to null,
            "the train station" to null,
        )
    }
}
