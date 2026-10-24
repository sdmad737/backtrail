package com.backtrail.app

import com.backtrail.app.contracts.RequestContext
import com.backtrail.app.trip.RequestGate
import org.junit.Assert.*
import org.junit.Test

class RequestGateTest {
    private val context = RequestContext("session", "request", "trip", 3, "checkpoint")

    @Test fun `reject results from any different session request revision trip or checkpoint`() {
        val gate = RequestGate().apply { begin(context) }
        listOf(
            context.copy(sessionId = "new-session"), context.copy(requestId = "new-request"),
            context.copy(tripId = "other-trip"), context.copy(tripRevision = 4),
            context.copy(checkpointId = "other-checkpoint"),
        ).forEach { assertFalse(gate.accepts(it)) }
        assertTrue(gate.accepts(context))
    }

    @Test fun `late result cannot consume replacement request`() {
        val gate = RequestGate().apply { begin(context); begin(context.copy(requestId = "new")) }
        assertFalse(gate.consume(context))
        assertTrue(gate.consume(context.copy(requestId = "new")))
        assertFalse(gate.consume(context.copy(requestId = "new")))
    }

    @Test fun `undo or session invalidation rejects outstanding work`() {
        val gate = RequestGate().apply { begin(context); invalidate() }
        assertFalse(gate.consume(context))
    }
}
