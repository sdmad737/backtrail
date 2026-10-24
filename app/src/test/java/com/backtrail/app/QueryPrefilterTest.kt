package com.backtrail.app

import com.backtrail.app.ai.CheckpointCatalog
import com.backtrail.app.ai.InferenceTrace
import com.backtrail.app.ai.QueryPrefilter
import com.backtrail.app.contracts.Checkpoint
import com.backtrail.app.contracts.ImageRef
import org.junit.Assert.*
import org.junit.Test

class QueryPrefilterTest {

    private val bridge = checkpoint("cp-bridge", 1, "Stone bridge", listOf("mossy stone parapet"))
    private val junction = checkpoint(
        "cp-junction", 2, "Trail junction", listOf("wooden signpost"),
        notes = "left my jacket on the bench here",
    )
    private val gate = checkpoint("cp-gate", 3, "Wooden gate", listOf("metal latch"))
    private val catalog = CheckpointCatalog.of(listOf(bridge, junction, gate))

    @Test fun `an unrelated question matches nothing so no inference is spent`() {
        assertTrue(QueryPrefilter.candidates("where is the helicopter", catalog).isEmpty())
        assertTrue(QueryPrefilter.candidates("", catalog).isEmpty())
    }

    @Test fun `a note is searchable even when the view is not`() {
        // "jacket" appears only in the user's note, never in the photo's fixed features.
        val matches = QueryPrefilter.candidates("where did I leave my jacket", catalog)
        assertEquals(listOf("cp-junction"), matches.map { it.checkpoint.id })
    }

    @Test fun `synonyms match in both directions`() {
        val coatCatalog = CheckpointCatalog.of(
            listOf(checkpoint("cp-seat", 1, "Picnic spot", notes = "forgot my coat")),
        )
        // Stored "coat", asked "jacket".
        assertEquals(1, QueryPrefilter.candidates("jacket", coatCatalog).size)
        // Stored "jacket", asked "coat".
        assertEquals(listOf("cp-junction"), QueryPrefilter.candidates("coat", catalog).map { it.checkpoint.id })
    }

    @Test fun `candidates are ranked by overlap and capped`() {
        val alsoBridge = checkpoint("cp-far", 4, "Wooden gate", listOf("metal latch"), notes = "bridge nearby")
        val ranked = QueryPrefilter.candidates(
            "stone bridge",
            CheckpointCatalog.of(listOf(alsoBridge, bridge, gate)),
        )
        assertEquals("cp-bridge", ranked.first().checkpoint.id)
        assertEquals(1, QueryPrefilter.candidates("stone bridge", catalog, limit = 1).size)
    }

    @Test fun `synthetic ids hide real ids and invented ids do not resolve`() {
        assertEquals(setOf("c1", "c2", "c3"), catalog.syntheticIds)
        assertEquals("cp-junction", catalog.realIdFor("c2"))
        assertNull(catalog.realIdFor("c9"))
        // The model cannot smuggle a real id through: only synthetic ids translate.
        assertNull(catalog.realIdFor("cp-junction"))
    }

    @Test fun `prompt lines carry the note and mark absent evidence`() {
        val lines = catalog.promptLines().lines()
        assertTrue(lines[1].contains("note=left my jacket on the bench here"))
        assertTrue(lines[0].contains("note=none"))
        assertTrue(CheckpointCatalog.of(listOf(checkpoint("cp-bare", 1, "Bare")))
            .promptLines().contains("features=none recorded"))
    }

    @Test fun `trace reports phases without leaking private route data`() {
        val line = InferenceTrace(
            op = "describe", backend = "GPU", modelDigestPrefix = "181938105e", runIndex = 2,
            initMs = 8100, generateMs = 1450, validateMs = 1, outcome = "success",
        ).line()
        assertTrue(line.contains("initMs=8100"))
        assertTrue(line.contains("generateMs=1450"))
        assertTrue(line.contains("outcome=success"))
        // No photo paths, note text or model output may ever reach logcat.
        assertFalse(line.contains("/"))
        assertFalse(line.contains("jacket"))
        assertTrue(InferenceTrace("describe", "CPU", "181938105e", 1).line().contains("initMs=-"))
    }

    private fun checkpoint(
        id: String,
        ordinal: Int,
        label: String,
        features: List<String> = emptyList(),
        notes: String = "",
    ) = Checkpoint(
        id = id,
        ordinal = ordinal,
        image = ImageRef("img-$id", "/tmp/$id.jpg", null),
        label = label,
        fixedFeatures = features,
        userNotes = notes,
    )
}
