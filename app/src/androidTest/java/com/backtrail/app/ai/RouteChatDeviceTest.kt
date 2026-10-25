package com.backtrail.app.ai

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backtrail.app.contracts.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in device smoke test. Reads real records; writes evidence only to private bench/. */
@RunWith(AndroidJUnit4::class)
class RouteChatDeviceTest {
    @Test fun conversationAndOriginalPhoto():Unit=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(app.filesDir,"outings.json")
        val before=source.readBytes()
        val outings=Json { ignoreUnknownKeys=true }.decodeFromString<List<Outing>>(before.toString(Charsets.UTF_8))
        val activeId=app.getSharedPreferences("travis",0).getString("active",null)
        val outing=outings.firstOrNull { it.id==activeId && it.memories.isNotEmpty() }
            ?: outings.first { it.memories.isNotEmpty() }
        val model=File(app.noBackupFilesDir,"models/gemma-4-E2B-it_Google_Tensor_G6.litertlm")
        assertTrue("Verified local G6 asset must already be installed",model.isFile)
        val report=JSONObject().put("outingId",outing.id).put("memoryCount",outing.memories.size)
            .put("routePointCount",outing.points.size).put("model",model.name).put("backend","NPU")
            .put("airplaneMode",Settings.Global.getInt(app.contentResolver,Settings.Global.AIRPLANE_MODE_ON,0))
            .put("currentFixProvided",false).put("photoSource","original saved photo, not a fresh capture")
        var raw:String?=null
        var trace:InferenceTrace?=null
        val service=LocalVisionService(model,app.cacheDir,InferenceBackend.NPU,
            nativeLibraryDir=app.applicationInfo.nativeLibraryDir,
            rawOutputSink={ raw=it },traceSink={ trace=it })
        val results=JSONArray()
        val failures=mutableListOf<String>()
        val history=mutableListOf<ConversationTurn>()
        fun request(kind:String)=RequestContext("route-chat-smoke",UUID.randomUUID().toString(),outing.id,outing.revision,kind)
        try {
            val landmark=outing.memories.last().title.take(70)
            val questions=listOf("What do you remember about $landmark?", "What did I pass before that place?", "How can I get back there along my recorded journey?")
            for(question in questions) {
                raw=null;trace=null
                val start=System.nanoTime()
                val result=service.chat(request("chat"),question,outing,null,history).toList().last()
                val entry=JSONObject().put("question",question).put("elapsedMillis",(System.nanoTime()-start)/1_000_000)
                    .put("raw",raw ?: JSONObject.NULL).put("trace",trace?.line() ?: JSONObject.NULL)
                when(result) {
                    is InferenceState.Success -> {
                        val answer=result.value
                        entry.put("answer",answer.answer).put("sourceIds",JSONArray(answer.sourceIds)).put("grounded",answer.grounded)
                        if(answer.sourceIds.any { id->outing.memories.none { it.id==id } })failures+="Unexpected citation"
                        history+=ConversationTurn(ConversationRole.USER,question)
                        history+=ConversationTurn(ConversationRole.TRAVIS,answer.answer,answer.sourceIds)
                    }
                    is InferenceState.Failure -> { entry.put("failure",result.message);failures+="chat: ${result.kind}" }
                    else -> { failures+="Chat did not finish" }
                }
                results.put(entry)
            }
            val photo=outing.memories.lastOrNull { it.photoPath.isNotBlank() && File(it.photoPath).isFile }
            if(photo==null)failures+="No original photo available" else {
                raw=null;trace=null
                val start=System.nanoTime()
                val result=service.askAboutPhoto(request("photo"),ImageRef(photo.id,photo.photoPath,photo.capturedAt),
                    Question.Text("Which permanent features in this photo would help me recognize this place again?")).toList().last()
                val entry=JSONObject().put("memoryId",photo.id).put("elapsedMillis",(System.nanoTime()-start)/1_000_000)
                    .put("raw",raw ?: JSONObject.NULL).put("trace",trace?.line() ?: JSONObject.NULL)
                when(result) {
                    is InferenceState.Success -> entry.put("answer",result.value.answer).put("confidence",result.value.confidence.name)
                    is InferenceState.Failure -> { entry.put("failure",result.message);failures+="photo: ${result.kind}" }
                    else -> failures+="Photo did not finish"
                }
                report.put("photo",entry)
            }
        } finally {
            service.closeAndJoin()
            val unchanged=MessageDigest.getInstance("SHA-256").digest(before).contentEquals(MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
            if(!unchanged)failures+="Outing metadata changed during test (check concurrent recording)"
            report.put("chat",results).put("recordsUnchanged",unchanged).put("failures",JSONArray(failures))
            File(app.filesDir,"bench").apply { mkdirs() }.resolve("route-chat-final.json").writeText(report.toString(2))
        }
        assertTrue("Inspect private files/bench/route-chat-final.json: $failures",failures.isEmpty())
    }
}
