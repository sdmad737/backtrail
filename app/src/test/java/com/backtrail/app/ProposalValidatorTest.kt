package com.backtrail.app

import com.backtrail.app.ai.ProposalValidator
import com.backtrail.app.contracts.DestinationProposal
import com.backtrail.app.contracts.Verdict
import org.junit.Assert.*
import org.junit.Test

class ProposalValidatorTest {
    @Test fun `destination must reference a supplied checkpoint`() {
        val raw = """{"action":"PROPOSE_TARGET","checkpointId":"car"}"""
        assertEquals(DestinationProposal.Target("car"), ProposalValidator.destination(raw, setOf("car")))
        rejects { ProposalValidator.destination(raw, setOf("bridge")) }
    }

    @Test fun `reject invented action and checkpoint on clarification`() {
        rejects { ProposalValidator.destination("""{"action":"ADVANCE_ROUTE"}""", emptySet()) }
        rejects {
            ProposalValidator.destination(
                """{"action":"ASK_CLARIFICATION","checkpointId":"car"}""", setOf("car"),
            )
        }
    }

    @Test fun `unknown stays unknown and directions are rejected`() {
        val raw = """{"verdict":"INSUFFICIENT_EVIDENCE","matchingFeatures":[],"conflictingFeatures":[]}"""
        assertEquals(Verdict.INSUFFICIENT_EVIDENCE, ProposalValidator.assessment(raw).verdict)
        rejects { ProposalValidator.assessment(raw.dropLast(1) + """, "directions":"turn left"}""") }
        rejects { ProposalValidator.assessment(raw.replace("INSUFFICIENT_EVIDENCE", "CERTAIN")) }
    }

    @Test fun `malformed unbounded and missing fields are rejected`() {
        rejects { ProposalValidator.description("```json\n{}\n```") }
        rejects { ProposalValidator.description("""{"label":"bench"}""") }
        rejects { ProposalValidator.description("""{"label":"","fixedFeatures":[]}""") }
        rejects { ProposalValidator.description("x".repeat(8_193)) }
        rejects {
            ProposalValidator.description("""{"label":"bench","fixedFeatures":["${"a".repeat(201)}"]}""")
        }
    }

    @Test fun `valid description contains only bounded editable evidence`() {
        val result = ProposalValidator.description("""{"label":"Blue bench","fixedFeatures":["wooden slats"]}""")
        assertEquals(listOf("wooden slats"), result.fixedFeatures)
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid proposal to be rejected") }
        catch (_: IllegalArgumentException) { /* expected, including serialization errors */ }
    }
}
