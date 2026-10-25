package com.backtrail.app.device

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in foreground hardware smoke test. Captured PCM is discarded, never saved or transcribed. */
@RunWith(AndroidJUnit4::class)
class VoiceHardwareDeviceTest {
    @Test fun microphoneAndOfflineSpeech():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext
        val report=JSONObject().put("scope","Microphone PCM capture and offline TTS callbacks; no transcription accuracy or audible speaker claim")
            .put("airplaneMode",Settings.Global.getInt(app.contentResolver,Settings.Global.AIRPLANE_MODE_ON,0))
        val failures=mutableListOf<String>()
        val voice=LocalVoice(app)
        var speech:LocalSpeech?=null
        try {
            val initiallyGranted=app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
            if(!initiallyGranted) {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${app.packageName} android.permission.RECORD_AUDIO")).use { it.readBytes() }
            }
            report.put("microphonePermissionGrantedByTest",!initiallyGranted)
            assertEquals(PackageManager.PERMISSION_GRANTED,app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            instrumentation.startActivitySync(Intent().setClassName(app.packageName,"com.backtrail.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            delay(300)
            val mic=JSONObject().put("requestedCaptureMillis",700).put("audioSaved",false).put("transcriptionRun",false)
            val captured=runCatching {
                withTimeout(10_000) {
                    voice.prepare()
                    val recording=async(Dispatchers.Default) { voice.record() }
                    delay(700)
                    voice.stop()
                    val wav=recording.await()
                    val header=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
                    check(wav.copyOfRange(0,4).toString(Charsets.US_ASCII)=="RIFF" && wav.copyOfRange(8,12).toString(Charsets.US_ASCII)=="WAVE")
                    check(header.getShort(20).toInt()==1 && header.getShort(22).toInt()==1)
                    check(header.getInt(24)==16_000 && header.getShort(34).toInt()==16)
                    check(header.getInt(40)==wav.size-44 && wav.size>=6444)
                    mic.put("status","captured PCM").put("sampleRate",16_000).put("channels",1).put("bitsPerSample",16)
                        .put("pcmBytes",wav.size-44).put("sampleContainsNonZero",wav.drop(44).any { it!=0.toByte() })
                }
            }
            captured.exceptionOrNull()?.let { mic.put("status","failed").put("error",it.javaClass.simpleName);failures+="Microphone capture failed" }
            report.put("microphone",mic)

            val events=Channel<Pair<Boolean,String>>(Channel.UNLIMITED)
            val speechEvents=JSONArray()
            instrumentation.runOnMainSync {
                speech=LocalSpeech(app) { speaking,message->events.trySend(speaking to message) }
                speech?.speak("Travis is ready.")
            }
            var started=false
            var outcome:String?=null
            withTimeoutOrNull(20_000) {
                while(outcome==null) {
                    val (speaking,message)=events.receive()
                    speechEvents.put(JSONObject().put("speaking",speaking).put("message",message))
                    if(speaking)started=true
                    else if(started && message.isEmpty())outcome="offline engine started and completed"
                    else if(message.contains("unavailable",true) || message.contains("No offline voice",true))outcome="unavailable with visible explanation"
                    else if(message.contains("failed",true) || message.contains("could not",true))outcome="failed with visible explanation"
                }
            }
            if(outcome==null) { outcome="timed out without completion or visible unavailability";failures+="TTS did not finish or report unavailable" }
            report.put("speech",JSONObject().put("status",outcome).put("onStartObserved",started).put("events",speechEvents))
        } catch(error:Throwable) {
            failures+="Hardware smoke interrupted: ${error.javaClass.simpleName}"
            throw error
        } finally {
            voice.stop()
            instrumentation.runOnMainSync { speech?.close() }
            report.put("failures",JSONArray(failures))
            File(app.filesDir,"bench").apply { mkdirs() }.resolve("voice-hardware-final.json").writeText(report.toString(2))
        }
        assertTrue("Inspect private files/bench/voice-hardware-final.json: $failures",failures.isEmpty())
    }
}
