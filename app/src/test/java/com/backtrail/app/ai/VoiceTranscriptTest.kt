package com.backtrail.app.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VoiceTranscriptTest {
    private fun wav(seconds:Int=1, channels:Short=1, rate:Int=16_000):ByteArray {
        val pcm=ByteArray(seconds*rate*channels*2)
        return ByteBuffer.allocate(44+pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(channels);putInt(rate);putInt(rate*channels*2);putShort((channels*2).toShort());putShort(16)
            put("data".toByteArray());putInt(pcm.size);put(pcm)
        }.array()
    }
    @Test fun `recorder format and maximum duration accepted`() { VoiceTranscript.validateWav(wav());VoiceTranscript.validateWav(wav(20)) }
    @Test(expected=IllegalArgumentException::class) fun `overlong audio rejected before native allocation`() { VoiceTranscript.validateWav(wav(21)) }
    @Test(expected=IllegalArgumentException::class) fun `stereo rejected`() { VoiceTranscript.validateWav(wav(channels=2)) }
    @Test(expected=IllegalArgumentException::class) fun `wrong sample rate rejected`() { VoiceTranscript.validateWav(wav(rate=8_000)) }
    @Test(expected=IllegalArgumentException::class) fun `truncated body rejected`() { VoiceTranscript.validateWav(wav().dropLast(2).toByteArray()) }
    @Test(expected=IllegalArgumentException::class) fun `empty audio rejected`() { VoiceTranscript.validateWav(wav(0)) }
    @Test fun `transcript normalizes whitespace but preserves actual spoken request`() {
        assertEquals("Where is my bag?",VoiceTranscript.parse("""{"transcript":"  Where is\\nmy bag?  "}""".replace("\\\\n","\\n")))
    }
    @Test(expected=IllegalArgumentException::class) fun `silence stays a visible failure`() { VoiceTranscript.parse("""{"transcript":"   "}""") }
    @Test(expected=IllegalArgumentException::class) fun `invented actions outside transcript schema rejected`() { VoiceTranscript.parse("""{"transcript":"Go back","action":"navigate"}""") }
    @Test(expected=IllegalArgumentException::class) fun `overlong transcript rejected`() { VoiceTranscript.parse("""{"transcript":"${"a".repeat(501)}"}""") }
}
