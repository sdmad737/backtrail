package com.backtrail.app.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Small PCM contract shared with the recorder. No remote speech recognizer is used. */
internal object VoiceTranscript {
    const val MAX_SECONDS=20
    private const val MAX_PCM_BYTES=16_000*2*MAX_SECONDS
    private val json=Json { ignoreUnknownKeys=false; isLenient=false; coerceInputValues=false }
    @Serializable private data class Response(val transcript:String)

    fun parse(raw:String):String {
        require(raw.length in 2..4_096)
        val text=json.decodeFromString<Response>(raw).transcript
        require(text.length<=Question.MAX_QUESTION_CHARS)
        return text.trim().replace(Regex("\\s+")," ").also { require(it.isNotBlank()) }
    }

    fun validateWav(bytes:ByteArray) {
        require(bytes.size in 44..MAX_PCM_BYTES+65_536)
        fun tag(at:Int,text:String)=at+text.length<=bytes.size && text.indices.all { bytes[at+it].toInt()==text[it].code }
        fun u16(at:Int)=(bytes[at].toInt() and 255) or ((bytes[at+1].toInt() and 255) shl 8)
        fun u32(at:Int)=(0..3).fold(0L) { n,i->n or ((bytes[at+i].toLong() and 255) shl (8*i)) }
        require(tag(0,"RIFF") && tag(8,"WAVE") && u32(4)+8==bytes.size.toLong())
        var cursor=12
        var format=false
        var data=false
        while(cursor+8<=bytes.size) {
            val size=u32(cursor+4)
            val start=cursor+8
            require(size<=bytes.size-start)
            if(tag(cursor,"fmt ")) {
                require(!format && size>=16)
                require(u16(start)==1 && u16(start+2)==1 && u32(start+4)==16_000L &&
                    u32(start+8)==32_000L && u16(start+12)==2 && u16(start+14)==16)
                format=true
            }
            if(tag(cursor,"data")) {
                require(!data && size in 1..MAX_PCM_BYTES && size%2L==0L)
                data=true
            }
            cursor=start+size.toInt()+(size.toInt() and 1)
        }
        require(format && data && cursor==bytes.size)
    }
}
