package com.backtrail.app

import com.backtrail.app.ai.AssistantValidator
import com.backtrail.app.ai.Confidence
import com.backtrail.app.ai.SafetyTopics
import com.backtrail.app.ai.TripMemoryIndex
import com.backtrail.app.contracts.Memory
import org.junit.Assert.*
import org.junit.Test

class AssistantTest {
    private val idMap = mapOf("s1" to "mem-aaa", "s2" to "mem-bbb")

    // ---- AssistantValidator.tripAnswer ----

    @Test fun `trip answer maps short ids to real ids and keeps grounding`() {
        val raw = """{"answer":"You parked by the bridge.","sourceIds":["s2","s1"],"grounded":true}"""
        val result = AssistantValidator.tripAnswer(raw, idMap)
        assertEquals(listOf("mem-bbb", "mem-aaa"), result.sourceIds)
        assertTrue(result.grounded)
        assertEquals("You parked by the bridge.", result.answer)
    }

    @Test fun `ungrounded trip answer has no sources`() {
        val raw = """{"answer":"I don't see that in your saved places.","sourceIds":[],"grounded":false}"""
        val result = AssistantValidator.tripAnswer(raw, idMap)
        assertFalse(result.grounded)
        assertTrue(result.sourceIds.isEmpty())
    }

    @Test fun `duplicate cited ids are collapsed`() {
        val raw = """{"answer":"Here.","sourceIds":["s1","s1"],"grounded":true}"""
        assertEquals(listOf("mem-aaa"), AssistantValidator.tripAnswer(raw, idMap).sourceIds)
    }

    @Test fun `trip answer rejects ids the model was never shown`() {
        rejects { AssistantValidator.tripAnswer("""{"answer":"x","sourceIds":["s3"],"grounded":true}""", idMap) }
        // A real id is not a valid short id.
        rejects { AssistantValidator.tripAnswer("""{"answer":"x","sourceIds":["mem-aaa"],"grounded":true}""", idMap) }
        rejects { AssistantValidator.tripAnswer("""{"answer":"x","sourceIds":["s1"],"grounded":true}""", emptyMap()) }
    }

    @Test fun `trip answer rejects more citations than memories shown`() {
        val raw = """{"answer":"x","sourceIds":["s1","s1","s1"],"grounded":true}"""
        rejects { AssistantValidator.tripAnswer(raw, idMap) }
    }

    @Test fun `grounded flag must agree with citations`() {
        rejects { AssistantValidator.tripAnswer("""{"answer":"x","sourceIds":[],"grounded":true}""", idMap) }
        rejects { AssistantValidator.tripAnswer("""{"answer":"x","sourceIds":["s1"],"grounded":false}""", idMap) }
    }

    @Test fun `trip answer rejects blank overlong fenced unknown-key and malformed output`() {
        rejects { AssistantValidator.tripAnswer("""{"answer":"   ","sourceIds":[],"grounded":false}""", idMap) }
        rejects {
            AssistantValidator.tripAnswer(
                """{"answer":"${"a".repeat(601)}","sourceIds":[],"grounded":false}""", idMap,
            )
        }
        val ok = """{"answer":"x","sourceIds":[],"grounded":false}"""
        rejects { AssistantValidator.tripAnswer("```json\n$ok\n```", idMap) }
        rejects { AssistantValidator.tripAnswer(ok.dropLast(1) + ""","directions":"go left"}""", idMap) }
        rejects { AssistantValidator.tripAnswer("""{"answer":"x","sourceIds":[]}""", idMap) }
        rejects { AssistantValidator.tripAnswer("", idMap) }
        rejects { AssistantValidator.tripAnswer("{" + " ".repeat(8_200) + "}", idMap) }
    }

    @Test fun `trip answer at the length limit is accepted`() {
        val raw = """{"answer":"${"a".repeat(600)}","sourceIds":[],"grounded":false}"""
        assertEquals(600, AssistantValidator.tripAnswer(raw, idMap).answer.length)
    }

    // ---- AssistantValidator.photoAnswer ----

    @Test fun `plain photo answer passes through with its confidence`() {
        val result = AssistantValidator.photoAnswer("""{"answer":"This looks like a coast redwood.","confidence":"MEDIUM"}""")
        assertEquals("This looks like a coast redwood.", result.answer)
        assertEquals(Confidence.MEDIUM, result.confidence)
        assertFalse(result.safetyRefusal)
    }

    @Test fun `safety verdict in a photo answer is replaced by the refusal`() {
        for (answer in listOf("These berries are edible.", "This plant is not poisonous.", "It is safe to eat.")) {
            val result = AssistantValidator.photoAnswer("""{"answer":"$answer","confidence":"HIGH"}""")
            assertEquals(answer, SafetyTopics.REFUSAL, result.answer)
            assertTrue(answer, result.safetyRefusal)
            // Never leaves the model's confidence attached to a refusal.
            assertEquals(Confidence.LOW, result.confidence)
        }
    }

    @Test fun `photo answer rejects bad confidence blank text and unknown keys`() {
        rejects { AssistantValidator.photoAnswer("""{"answer":"A tree.","confidence":"CERTAIN"}""") }
        rejects { AssistantValidator.photoAnswer("""{"answer":"A tree.","confidence":"high"}""") }
        rejects { AssistantValidator.photoAnswer("""{"answer":"","confidence":"LOW"}""") }
        rejects { AssistantValidator.photoAnswer("""{"answer":"A tree.","confidence":"LOW","extra":1}""") }
        rejects { AssistantValidator.photoAnswer("""{"answer":"A tree."}""") }
    }

    // ---- AssistantValidator.reminder ----

    @Test fun `reminder on return carries a trimmed task`() {
        val result = AssistantValidator.reminder("""{"action":"REMIND_ON_RETURN","task":"  buy water  "}""")
        assertTrue(result.remindOnReturn)
        assertEquals("buy water", result.task)
    }

    @Test fun `reminder none has no task`() {
        val result = AssistantValidator.reminder("""{"action":"NONE","task":""}""")
        assertFalse(result.remindOnReturn)
        assertEquals("", result.task)
    }

    @Test fun `reminder rejects blank overlong and unsupported actions`() {
        rejects { AssistantValidator.reminder("""{"action":"REMIND_ON_RETURN","task":"   "}""") }
        rejects { AssistantValidator.reminder("""{"action":"REMIND_ON_RETURN","task":"${"a".repeat(201)}"}""") }
        rejects { AssistantValidator.reminder("""{"action":"CALL_SOMEONE","task":"mom"}""") }
        rejects { AssistantValidator.reminder("""{"action":"remind_on_return","task":"x"}""") }
        rejects { AssistantValidator.reminder("```json\n{\"action\":\"NONE\",\"task\":\"\"}\n```") }
        assertTrue(AssistantValidator.reminder("""{"action":"REMIND_ON_RETURN","task":"${"a".repeat(200)}"}""").remindOnReturn)
    }

    // ---- SafetyTopics ----

    @Test fun `safety questions are detected`() {
        for (q in listOf(
            "Can I eat these berries?", "is this mushroom poisonous", "is the stream water drinkable",
            "is it safe to touch this plant",
        )) assertTrue(q, SafetyTopics.isSafetyQuestion(q))
    }

    @Test fun `ordinary identification questions are not safety questions`() {
        for (q in listOf("what kind of tree is this", "how old is this tree", "what is that building")) {
            assertFalse(q, SafetyTopics.isSafetyQuestion(q))
        }
    }

    @Test fun `safety verdicts in answers are detected`() {
        assertTrue(SafetyTopics.isSafetyVerdict("These berries are edible"))
        assertTrue(SafetyTopics.isSafetyVerdict("This plant is not poisonous"))
        assertFalse(SafetyTopics.isSafetyVerdict("This is a coast redwood"))
    }

    // ---- TripMemoryIndex companion ----

    private val hour = 3_600_000L
    private val now = 100 * hour
    private fun mem(id: String, at: Long, description: String = "", note: String = "") =
        Memory(id = id, title = id, capturedAt = at, description = description, note = note)

    @Test fun `recent keeps only memories inside the window and not in the future`() {
        val memories = listOf(
            mem("old", now - 5 * hour),
            mem("fresh", now - 30 * 60_000L),
            mem("edge", now - 2 * hour),
            mem("now", now),
            mem("future", now + hour),
        )
        val kept = TripMemoryIndex.recent(memories, now, 2.0).map { it.id }
        assertEquals(listOf("fresh", "edge", "now"), kept)
    }

    @Test fun `recent with no window keeps everything including future`() {
        val memories = listOf(mem("a", 0), mem("b", now + hour))
        assertEquals(memories, TripMemoryIndex.recent(memories, now, null))
    }

    @Test fun `recent supports fractional hours and rejects non-positive windows`() {
        val memories = listOf(mem("a", now - 20 * 60_000L), mem("b", now - 40 * 60_000L))
        assertEquals(listOf("a"), TripMemoryIndex.recent(memories, now, 0.5).map { it.id })
        rejects { TripMemoryIndex.recent(memories, now, 0.0) }
    }

    @Test fun `document text joins description and note and skips blanks`() {
        assertEquals("Red bench. Left my hat", TripMemoryIndex.documentText(mem("a", 0, "Red bench", "Left my hat")))
        assertEquals("Red bench", TripMemoryIndex.documentText(mem("a", 0, "Red bench", "  ")))
        assertEquals("Left my hat", TripMemoryIndex.documentText(mem("a", 0, "", "Left my hat")))
        assertEquals("", TripMemoryIndex.documentText(mem("a", 0)))
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid input to be rejected") }
        catch (_: IllegalArgumentException) { /* expected, including serialization errors */ }
    }
}
