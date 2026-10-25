package com.backtrail.app.ai

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backtrail.app.contracts.InferenceState
import com.backtrail.app.contracts.RequestContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional existing recording only. Never synthesizes a voice sample or claims a microphone test. */
@RunWith(AndroidJUnit4::class)
class VoiceDeviceTest {
    @Test fun transcribeExistingRecording():Unit=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val report=JSONObject().put("source","existing private bench audio; microphone capture not tested")
            .put("airplaneMode",Settings.Global.getInt(app.contentResolver,Settings.Global.AIRPLANE_MODE_ON,0))
        val output=File(app.filesDir,"bench").apply { mkdirs() }.resolve("voice-transcription-final.json")
        val name=InstrumentationRegistry.getArguments().getString("audioFile")
        val folder=File(app.filesDir,"bench-audio")
        val audio=if(name!=null)File(folder,File(name).name).takeIf { it.isFile }
            else folder.listFiles()?.filter { it.extension.lowercase()=="wav" }?.sortedBy { it.name }?.firstOrNull {
                runCatching { VoiceTranscript.validateWav(it.readBytes()) }.isSuccess
            }
        if(audio==null) {
            output.writeText(report.put("status","unverified: no compatible existing recording").toString(2))
            assumeTrue("No existing <=20 second 16kHz mono PCM16 WAV; live microphone remains unverified",false)
            return@runBlocking
        }
        val model=File(app.noBackupFilesDir,"models/gemma-4-E2B-it_Google_Tensor_G6.litertlm")
        assertTrue("Local G6 model must already be installed",model.isFile)
        var trace:InferenceTrace?=null
        var raw:String?=null
        val service=LocalVisionService(model,app.cacheDir,InferenceBackend.NPU,
            nativeLibraryDir=app.applicationInfo.nativeLibraryDir,rawOutputSink={ raw=it },traceSink={ trace=it })
        var result:InferenceState<String>?=null
        try {
            result=service.transcribe(RequestContext("voice-smoke",UUID.randomUUID().toString(),"voice-smoke",0,"audio"),audio.readBytes()).toList().last()
            report.put("audioFile",audio.name).put("trace",trace?.line() ?: JSONObject.NULL).put("raw",raw ?: JSONObject.NULL)
            when(val final=result) {
                is InferenceState.Success -> report.put("transcript",final.value).put("status","runtime success; transcription accuracy needs review")
                is InferenceState.Failure -> report.put("failure",final.message).put("status","runtime failure")
                else -> report.put("status","incomplete")
            }
        } finally {
            service.closeAndJoin()
            output.writeText(report.toString(2))
        }
        assertTrue("Inspect private files/bench/voice-transcription-final.json",result is InferenceState.Success)
    }
}
