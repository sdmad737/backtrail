package com.backtrail.app.trip

import android.content.Context
import android.util.AtomicFile
import com.backtrail.app.contracts.*
import com.backtrail.app.ai.PlaceNaming
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One application-wide store serializes service and UI writes. Original photos live beside metadata. */
class OutingStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.filesDir,"outings.json"))
    val mediaDirectory = File(context.filesDir,"landmarks").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val deletedIds = mutableSetOf<String>()
    private val mutable = MutableStateFlow<List<Outing>>(emptyList())
    val outings = mutable.asStateFlow()
    val loadError = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val loaded = scope.async {
        lock.withLock {
            try {
                if(file.baseFile.exists()) {
                    mutable.value = json.decodeFromString<List<Outing>>(file.openRead().bufferedReader().use { it.readText() }).map {
                        // A terminated process does not imply an active OS tracking service.
                        val old=it
                        val memories=old.memories.map { memory ->
                            if(!memory.titleEdited && memory.title==memory.note.lineSequence().firstOrNull()?.take(40) && memory.note.isNotBlank())
                                memory.copy(title=PlaceNaming.initial(memory.note)) else memory
                        }
                        val renamed=old.copy(memories=memories,revision=old.revision+if(memories!=old.memories)1 else 0,
                            name=if(old.name=="Today's outing") "Journey · "+java.text.SimpleDateFormat("MMM d · h:mm a",java.util.Locale.getDefault()).format(java.util.Date(old.startedAt)) else old.name)
                        if(renamed.phase==OutingPhase.RECORDING) renamed.copy(phase=OutingPhase.PAUSED,segment=renamed.segment+1) else renamed
                    }
                }
            } catch(_:Exception) { loadError.value="Saved outings could not be read. Original files were preserved." }
        }
    }
    suspend fun save(outing: Outing) { mutate(outing.id) { outing } }
    suspend fun awaitLoaded() { loaded.await() }
    suspend fun mutate(id: String, change: (Outing?) -> Outing) {
        loaded.await()
        withContext(Dispatchers.IO) {
            lock.withLock {
                check(loadError.value==null) { "Stored data could not be loaded; refusing to overwrite it." }
                check(id !in deletedIds) { "This journey was deleted; refusing a late write." }
                val previous=mutable.value.find { it.id==id }
                val next=change(previous)
                require(next.id==id) { "An outing mutation cannot change its owner." }
                if(next==previous) return@withLock
                val items=(mutable.value.filterNot { it.id==id }+next).sortedByDescending { it.startedAt }
                val output=file.startWrite()
                try { output.write(json.encodeToString(items).toByteArray()); file.finishWrite(output); mutable.value=items }
                catch(error:Exception) { file.failWrite(output); throw error }
            }
        }
    }
    suspend fun delete(id: String): Outing? {
        loaded.await()
        return withContext(Dispatchers.IO) {
            lock.withLock {
                check(loadError.value == null) { "Stored data could not be loaded." }
                val removed = mutable.value.firstOrNull { it.id == id } ?: return@withLock null
                val items = mutable.value.filterNot { it.id == id }
                val output = file.startWrite()
                try {
                    output.write(json.encodeToString(items).toByteArray())
                    file.finishWrite(output)
                    deletedIds.add(id)
                    mutable.value = items
                } catch (error: Exception) { file.failWrite(output); throw error }
                val keptPhotos = items.flatMap { it.memories }.map { it.photoPath }.toSet()
                val root = runCatching { mediaDirectory.canonicalPath + File.separator }.getOrNull()
                    ?: return@withLock removed
                removed.memories.map { it.photoPath }.filter { it.isNotBlank() && it !in keptPhotos }.distinct().forEach { path ->
                    runCatching {
                        val photo = File(path)
                        if (photo.canonicalPath.startsWith(root) && photo.isFile) photo.delete()
                    }
                }
                removed
            }
        }
    }
    companion object {
        @Volatile private var instance: OutingStore?=null
        fun get(context: Context): OutingStore = instance ?: synchronized(this) { instance ?: OutingStore(context.applicationContext).also { instance=it } }
    }
}
