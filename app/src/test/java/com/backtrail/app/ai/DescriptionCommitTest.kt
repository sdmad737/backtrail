package com.backtrail.app.ai

import com.backtrail.app.contracts.CheckpointDescription
import com.backtrail.app.contracts.Memory
import com.backtrail.app.contracts.Outing
import com.backtrail.app.contracts.RequestContext
import org.junit.Assert.*
import org.junit.Test

class DescriptionCommitTest {
    private val memory = Memory("memory", "My jacket", "I left my jacket here.", "/local/photo.jpg", 100)
    private val outing = Outing(id = "outing", memories = listOf(memory), revision = 4)
    private val request = RequestContext("session", "request", "outing", 4, "memory")
    private val description = CheckpointDescription("Stone bench", listOf("Stone bench beside a gate"))

    @Test fun `edit committed while result waits for disk cannot be overwritten`() {
        val edited = outing.copy(revision = 5, memories = listOf(memory.copy(description = "My corrected observation", indexing = "Edited by you")))
        val decision = DescriptionCommit.apply(edited, request, memory.photoPath, description)
        assertFalse(decision.accepted)
        assertSame(edited, decision.outing)
    }

    @Test fun `photo replacement or removed record rejects the pending description`() {
        assertFalse(DescriptionCommit.apply(outing, request, "/local/older.jpg", description).accepted)
        assertFalse(DescriptionCommit.apply(outing.copy(memories = emptyList()), request, memory.photoPath, description).accepted)
        assertFalse(DescriptionCommit.apply(outing.copy(id = "other"), request, memory.photoPath, description).accepted)
    }

    @Test fun `accepted observations preserve original evidence and invalidate old catalogs`() {
        val decision = DescriptionCommit.apply(outing, request, memory.photoPath, description)
        assertTrue(decision.accepted)
        assertEquals(5L, decision.outing.revision)
        val saved = decision.outing.memories.single()
        assertEquals(memory.note, saved.note)
        assertEquals(memory.title, saved.title)
        assertEquals(memory.photoPath, saved.photoPath)
        assertEquals(memory.capturedAt, saved.capturedAt)
        assertEquals("Stone bench beside a gate", saved.description)
        assertFalse(DescriptionCommit.apply(decision.outing, request, memory.photoPath, description).accepted)
    }
}
