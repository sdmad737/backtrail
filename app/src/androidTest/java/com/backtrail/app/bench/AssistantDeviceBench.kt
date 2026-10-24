package com.backtrail.app.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backtrail.app.ai.InferenceBackend
import com.backtrail.app.ai.InferenceTrace
import com.backtrail.app.ai.LocalEmbedder
import com.backtrail.app.ai.LocalVisionService
import com.backtrail.app.ai.Question
import com.backtrail.app.ai.TripMemoryIndex
import com.backtrail.app.contracts.ImageRef
import com.backtrail.app.contracts.InferenceState
import com.backtrail.app.contracts.Memory
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
 * On-device run of the assistant features (trip questions, photo questions, reminders, voice)
 * over the bench photos as a synthetic private garden walk. Writes raw answers and timings to
 * files/bench/assistant-<tag>.json for review; nothing here is a pass/fail quality test.
 *
 * Inputs (copied in with `adb shell run-as`, never committed):
 *   no_backup/models/<model>.litertlm   files/bench-photos/<name>.jpg   files/bench-audio/<name>.wav
 *
 *   adb shell am instrument -w -e class com.backtrail.app.bench.AssistantDeviceBench#assistant \
 *     -e backend NPU -e tag npu com.backtrail.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class AssistantDeviceBench {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val modelsDir = File(context.noBackupFilesDir, "models")
    private val photosDir = File(context.filesDir, "bench-photos")
    private val audioDir = File(context.filesDir, "bench-audio")
    private val outDir = File(context.filesDir, "bench").apply { mkdirs() }
    private val nativeLibraryDir = context.applicationInfo.nativeLibraryDir

    private fun arg(name: String, default: String) = args.getString(name) ?: default
    private fun photos() = (photosDir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") } ?: emptyArray()).sortedBy { it.name }

    private var lastRaw: String? = null
    private var lastTrace: InferenceTrace? = null

    @Test
    fun assistant(): Unit = runBlocking {
        val model = File(modelsDir, arg("model", "gemma-4-E2B-it_Google_Tensor_G6.litertlm"))
        val backend = InferenceBackend.valueOf(arg("backend", "NPU"))
        val vision = InferenceBackend.valueOf(arg("vision", backend.name))
        val embedModel = File(modelsDir, arg("embedModel", "embeddinggemma-2-740m_Google_Tensor_G6.litertlm"))
        val embedBackend = InferenceBackend.valueOf(arg("embedBackend", "NPU"))
        val tag = arg("tag", backend.name.lowercase())

        val report = JSONObject().put("op", "assistant").put("tag", tag).put("model", model.name)
            .put("backend", backend.name).put("visionBackend", vision.name)
            .put("embedModel", embedModel.name).put("embedBackend", embedBackend.name)
            .put("rssMbBefore", rssMb())
        val service = LocalVisionService(
            model, context.cacheDir, backend, vision, nativeLibraryDir,
            rawOutputSink = { lastRaw = it },
            traceSink = { lastTrace = it },
        )
        val embedder = LocalEmbedder(embedModel, context.cacheDir, embedBackend, embedBackend, nativeLibraryDir)
        val now = System.currentTimeMillis()
        try {
            // 1. Memories from the bench photos.
            val notes = mapOf(
                "pergola-a" to "Left my water bottle on the fire bowl",
                "gatepath-a" to "Remind me to close the gate on the way back",
            )
            val describeResults = JSONArray()
            val memories = photos().mapIndexed { index, photo ->
                val start = resetAndStart()
                val final = runCatching {
                    service.describe(request(photo.name), ImageRef(UUID.randomUUID().toString(), photo.absolutePath, null)).toList().last()
                }
                val ms = msSince(start)
                val success = (final.getOrNull() as? InferenceState.Success)?.value
                val id = photo.nameWithoutExtension
                val memory = Memory(
                    id = id,
                    title = success?.label ?: id,
                    note = notes[id] ?: "",
                    photoPath = photo.absolutePath,
                    capturedAt = now - index * 10 * 60_000L,
                    description = success?.fixedFeatures?.joinToString(" · ") ?: "",
                )
                describeResults.put(callJson(ms, final).put("photo", photo.name).put("id", id)
                    .put("title", memory.title).put("description", memory.description).put("note", memory.note)
                    .put("minutesAgo", index * 10))
                Log.i(TAG, "assistant tag=$tag describe ${photo.name} ms=$ms ok=${success != null} ${lastTrace?.line()}")
                memory
            }
            report.put("describe", describeResults).put("rssMbAfterDescribe", rssMb())

            // 2. Index + trip questions.
            val index = TripMemoryIndex(embedder)
            val indexStart = System.nanoTime()
            val indexError = runCatching { index.index(memories) }.exceptionOrNull()
            report.put("indexMs", msSince(indexStart)).put("embedInitMs", embedder.lastInitMs)
                .put("indexError", indexError?.let { err(it) } ?: JSONObject.NULL)
            Log.i(TAG, "assistant tag=$tag index n=${memories.size} ms=${report.get("indexMs")} error=${indexError?.javaClass?.simpleName}")

            val tripQuestions = listOf<Pair<String, Double?>>(
                "Where did I leave my water bottle?" to null,
                "Which spot had the stone lantern?" to null,
                "What did I see near the pond in the last 30 minutes?" to 0.5,
                "Where is the swimming pool?" to null,
            )
            val tripResults = JSONArray()
            for ((text, withinHours) in tripQuestions) {
                val entry = JSONObject().put("question", text).put("withinHours", withinHours ?: JSONObject.NULL)
                val rankStart = System.nanoTime()
                val ranked = runCatching { index.rank(text, memories, nowMillis = now, withinHours = withinHours, limit = 3) }
                entry.put("rankMs", msSince(rankStart))
                ranked.exceptionOrNull()?.let { entry.put("rankError", err(it)) }
                val top = ranked.getOrNull().orEmpty()
                entry.put("ranked", JSONArray(top.map { JSONObject().put("id", it.memory.id).put("score", it.score.toDouble()) }))
                val start = resetAndStart()
                val final = runCatching {
                    service.askAboutTrip(request("trip"), Question.Text(text), top.map { it.memory }, now).toList().last()
                }
                val ms = msSince(start)
                callJson(ms, final, entry)
                (final.getOrNull() as? InferenceState.Success)?.value?.let {
                    entry.put("answer", it.answer).put("sourceIds", JSONArray(it.sourceIds)).put("grounded", it.grounded)
                }
                tripResults.put(entry)
                Log.i(TAG, "assistant tag=$tag trip q=\"$text\" top=${top.map { it.memory.id }} ms=$ms " +
                    "grounded=${entry.opt("grounded")} sources=${entry.opt("sourceIds")} ${stateName(final)} ${lastTrace?.line()}")
            }
            report.put("trip", tripResults)

            // 3. Photo questions.
            val photoQuestions = listOf(
                "redwood-a" to "What kind of tree is this?",
                "pine-a" to "What kind of tree is this?",
                "pergola-b" to "What is the round bowl for?",
                "redwood-b" to "Can I eat anything growing here?",
            )
            val photoResults = JSONArray()
            for ((id, text) in photoQuestions) {
                val photo = photos().firstOrNull { it.nameWithoutExtension == id }
                val entry = JSONObject().put("photo", id).put("question", text)
                if (photo == null) {
                    photoResults.put(entry.put("error", "photo missing"))
                    Log.i(TAG, "assistant tag=$tag photo $id missing")
                    continue
                }
                val start = resetAndStart()
                val final = runCatching {
                    service.askAboutPhoto(request(id), ImageRef(UUID.randomUUID().toString(), photo.absolutePath, null), Question.Text(text)).toList().last()
                }
                val ms = msSince(start)
                callJson(ms, final, entry)
                (final.getOrNull() as? InferenceState.Success)?.value?.let {
                    entry.put("answer", it.answer).put("confidence", it.confidence.name).put("safetyRefusal", it.safetyRefusal)
                }
                // A safety refusal must not reach the model: no trace means no model call.
                entry.put("modelCalled", lastTrace != null)
                photoResults.put(entry)
                Log.i(TAG, "assistant tag=$tag photo $id q=\"$text\" ms=$ms conf=${entry.opt("confidence")} " +
                    "refusal=${entry.opt("safetyRefusal")} modelCalled=${lastTrace != null} ${stateName(final)} ${lastTrace?.line()}")
            }
            report.put("photo", photoResults)

            // 4. Reminders.
            val reminderNotes = listOf(
                "pick up honey from the farm stand here on the way back",
                "nice view of the pond",
                "remind me to close the gate when I come back",
            )
            val reminderResults = JSONArray()
            for (text in reminderNotes) {
                val entry = JSONObject().put("note", text)
                val start = resetAndStart()
                val final = runCatching { service.parseReminder(request("reminder"), Question.Text(text)).toList().last() }
                val ms = msSince(start)
                callJson(ms, final, entry)
                (final.getOrNull() as? InferenceState.Success)?.value?.let {
                    entry.put("remindOnReturn", it.remindOnReturn).put("task", it.task)
                }
                reminderResults.put(entry)
                Log.i(TAG, "assistant tag=$tag reminder note=\"$text\" ms=$ms remind=${entry.opt("remindOnReturn")} " +
                    "task=${entry.opt("task")} ${stateName(final)} ${lastTrace?.line()}")
            }
            report.put("reminder", reminderResults)

            // 5. Voice questions (audio on this backend is untested).
            val wavs = (audioDir.listFiles { f -> f.extension.lowercase() == "wav" } ?: emptyArray()).sortedBy { it.name }
            val voiceResults = JSONArray()
            val voicePool = memories.take(3)
            for (wav in wavs) {
                val entry = JSONObject().put("audio", wav.name).put("bytes", wav.length())
                    .put("pool", JSONArray(voicePool.map { it.id }))
                val start = resetAndStart()
                val final = runCatching {
                    service.askAboutTrip(request("voice"), Question.Audio(wav.readBytes()), voicePool, now).toList().last()
                }
                val ms = msSince(start)
                callJson(ms, final, entry)
                (final.getOrNull() as? InferenceState.Success)?.value?.let {
                    entry.put("answer", it.answer).put("sourceIds", JSONArray(it.sourceIds)).put("grounded", it.grounded)
                }
                voiceResults.put(entry)
                Log.i(TAG, "assistant tag=$tag voice ${wav.name} ms=$ms grounded=${entry.opt("grounded")} " +
                    "${stateName(final)} ${lastTrace?.line()}")
            }
            report.put("voice", voiceResults)
            report.put("rssMbAfter", rssMb())
        } catch (failure: Throwable) {
            report.put("error", err(failure))
        } finally {
            service.close()
            embedder.close()
        }
        File(outDir, "assistant-$tag.json").writeText(report.toString(2))
        Log.i(TAG, "assistant tag=$tag done error=${report.optString("error")}")
    }

    private fun resetAndStart(): Long {
        lastRaw = null
        lastTrace = null
        return System.nanoTime()
    }

    /** Timing, trace, raw text and final state of one call, into [into]. */
    private fun callJson(ms: Long, final: Result<InferenceState<*>>, into: JSONObject = JSONObject()): JSONObject {
        val t = lastTrace
        into.put("ms", ms)
            .put("engine", t?.engine ?: JSONObject.NULL)
            .put("initMs", t?.initMs ?: JSONObject.NULL)
            .put("generateMs", t?.generateMs ?: JSONObject.NULL)
            .put("outcome", t?.outcome ?: JSONObject.NULL)
            .put("raw", lastRaw ?: JSONObject.NULL)
            .put("state", stateName(final))
        final.exceptionOrNull()?.let { into.put("error", err(it)) }
        (final.getOrNull() as? InferenceState.Failure)?.let { into.put("failureKind", it.kind.name).put("failureMessage", it.message) }
        return into
    }

    private fun stateName(final: Result<InferenceState<*>>): String =
        final.fold({ "state=${it.javaClass.simpleName}" }, { "exception=${it.javaClass.simpleName}" })

    private fun request(checkpoint: String) = RequestContext("bench", UUID.randomUUID().toString(), "bench", 0, checkpoint)

    private fun err(t: Throwable) = "${t.javaClass.simpleName}: ${t.message}"

    private fun msSince(start: Long) = (System.nanoTime() - start) / 1_000_000

    private fun rssMb(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmRSS:") }
        ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.div(1024) ?: -1

    private companion object {
        const val TAG = "TravisBench"
    }
}
