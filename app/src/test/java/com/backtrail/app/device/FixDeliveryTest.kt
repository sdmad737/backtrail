package com.backtrail.app.device

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FixDeliveryTest {
    @Test fun disablingAndReenablingDuringWriteDeliversCommittedReminderButNeverOldFix() = runBlocking {
        val delivery = FixDelivery()
        val writing = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        var reminderDelivered = false
        var published = false
        val task = launch {
            delivery.commit(delivery.generation, persist = {
                writing.complete(Unit)
                finishWrite.await()
            }, deliverCommitted = { reminderDelivered = true }, publish = { published = true })
        }
        writing.await()
        delivery.interrupt() // The provider can re-enable before the disk write finishes.
        finishWrite.complete(Unit)
        task.join()
        assertTrue(reminderDelivered)
        assertFalse(published)
        delivery.commit(delivery.generation, {}, {}, { published = true })
        assertTrue(published)
    }

    @Test fun queuedPreOutageSampleCannotWriteOrPublishAfterRecovery() = runBlocking {
        val delivery = FixDelivery()
        val queuedGeneration = delivery.generation
        delivery.interrupt()
        delivery.commit(queuedGeneration, { fail("Old callback reached storage") },
            { fail("Old callback delivered a reminder") }, { fail("Old callback reached guidance") })
    }
}
