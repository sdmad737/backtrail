package com.backtrail.app.trip

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backtrail.app.contracts.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneyDeletionDeviceTest {
    @Test fun deleteCommitsBeforeRemovingPhotosAndRejectsLateWrites():Unit=runBlocking {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone") || Build.MODEL.contains("Emulator",true))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=OutingStore.get(context);store.awaitLoaded()
        val id="delete-test-${UUID.randomUUID()}"
        val owned=File(store.mediaDirectory,"$id.jpg").apply { writeText("test-photo") }
        val shared=File(store.mediaDirectory,"$id-shared.jpg").apply { writeText("shared-test-photo") }
        val external=File(context.filesDir,"$id-external.txt").apply { writeText("outside-photo-directory") }
        val removed=Outing(id=id,memories=listOf(Memory("a","A",photoPath=owned.path,capturedAt=1),Memory("b","B",photoPath=shared.path,capturedAt=1),Memory("c","C",photoPath=external.path,capturedAt=1)))
        val keep=Outing(id="$id-keep",memories=listOf(Memory("d","D",photoPath=shared.path,capturedAt=1)))
        val originalIds=store.outings.value.map { it.id }.toSet()
        try {
            store.save(removed);store.save(keep)
            store.delete(id)
            assertFalse(store.outings.value.any { it.id==id });assertFalse(owned.exists())
            assertTrue(shared.exists());assertTrue(external.exists())
            assertTrue(store.outings.value.map { it.id }.containsAll(originalIds))
            assertFalse(File(context.filesDir,"outings.json").readText().contains("\"id\":\"$id\""))
            var rejected=false
            try { store.mutate(id) { removed } } catch(_:IllegalStateException) { rejected=true }
            assertTrue("A queued callback cannot recreate a deleted journey",rejected)
        } finally { store.delete(id);store.delete(keep.id);owned.delete();shared.delete();external.delete() }
    }
}
