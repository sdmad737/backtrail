package com.backtrail.app.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Foreground, explicitly requested audio only. No files, background mic, or network recognizer. */
class LocalVoice(private val context: Context) {
    private val recordingMutex=Mutex()
    @Volatile private var stopRequested = false
    fun stop() { stopRequested = true }
    fun prepare() { stopRequested = false }
    suspend fun record(): ByteArray = recordingMutex.withLock { withContext(Dispatchers.IO) {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Allow microphone access to use voice." }
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "This microphone does not support local recording." }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16_000,
            AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,3200))
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not start." }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone is busy." }
            val pcm = ByteArrayOutputStream()
            val buffer = ByteArray(3200)
            while(!stopRequested && pcm.size() < 16_000 * 2 * 20) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(buffer,0,minOf(buffer.size,16_000*2*20-pcm.size()))
                check(count >= 0) { "Microphone recording failed." }
                if(count > 0) pcm.write(buffer,0,count)
            }
            check(pcm.size() >= 6400) { "Recording was too short. Tap Voice and ask your question." }
            wav(pcm.toByteArray())
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
    }
    companion object {
        internal fun wav(pcm: ByteArray): ByteArray {
            val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36+pcm.size).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
            return header.array()+pcm
        }
    }
}

/** Uses only an already installed offline Android voice. */
class LocalSpeech(context:Context,private val status:(Boolean,String)->Unit) {
    private var ready=false
    private var initialized=false
    private var failure="Read aloud is unavailable on this phone."
    private var pending:String?=null
    private var engine:TextToSpeech?=null
    init {
        engine=TextToSpeech(context) { result ->
            initialized=true
            val tts=engine
            if(result==TextToSpeech.SUCCESS && tts!=null) {
                val local=tts.voices?.filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                val voice=local?.firstOrNull { it.locale.language==Locale.getDefault().language }
                    ?:local?.firstOrNull { it.locale.language=="en" }
                ready=voice!=null && tts.setVoice(voice)==TextToSpeech.SUCCESS
                if(!ready) { failure="No offline voice is installed on this phone.";status(false,failure) }
                else pending?.let { speak(it) }
            } else status(false,"Read aloud is unavailable on this phone.")
        }
        engine?.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
            override fun onStart(id:String?) { status(true,"Reading aloud") }
            override fun onDone(id:String?) { status(false,"") }
            @Deprecated("Android callback") override fun onError(id:String?) { status(false,"Read aloud failed.") }
        })
    }
    fun speak(text:String) {
        if(!ready) { if(initialized)status(false,failure)else { pending=text;status(false,"Loading the installed offline voice…") };return }
        pending=null
        if(engine?.speak(text.take(3000),TextToSpeech.QUEUE_FLUSH,null,"travis-reply")!=TextToSpeech.SUCCESS)
            status(false,"Read aloud could not start.")
    }
    fun stop() { pending=null;engine?.stop();status(false,"") }
    fun close() { engine?.stop();engine?.shutdown();engine=null }
}
