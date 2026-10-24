package com.backtrail.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.backtrail.app.ai.LocalVisionService
import com.backtrail.app.ai.ModelStore
import com.backtrail.app.contracts.*
import com.backtrail.app.trip.PhotoImporter
import com.backtrail.app.trip.RequestGate
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SmokeState(
    val modelReady: Boolean = false,
    val image: ImageRef? = null,
    val busy: Boolean = false,
    val canCancel: Boolean = false,
    val message: String = "Import the model and select a local photo to begin.",
    val result: CheckpointDescription? = null,
)

/** Composition root for the bootstrap screen. There is deliberately no route state here. */
class BacktrailViewModel(application: Application) : AndroidViewModel(application) {
    private val models = ModelStore(application)
    private val photos = PhotoImporter(application)
    private val vision: VisionService = LocalVisionService(models.file, application.cacheDir)
    private val gate = RequestGate()
    private val sessionId = UUID.randomUUID().toString()
    private var inferenceJob: Job? = null
    private val mutableState = MutableStateFlow(SmokeState(modelReady = models.ready))
    val state = mutableState.asStateFlow()

    fun importModel(uri: Uri) {
        if (state.value.busy) return
        gate.invalidate()
        mutableState.update { it.copy(busy = true, result = null, message = "Copying and verifying the model… Keep this app open.") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { models.import(uri) }
                mutableState.update { it.copy(modelReady = true, message = "Model verified and stored on this phone.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(message = failure.message ?: "Model import failed.") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun importPhoto(uri: Uri) {
        if (state.value.busy) return
        gate.invalidate()
        mutableState.update { it.copy(busy = true, result = null, message = "Preparing selected photo…") }
        viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { photos.import(uri) }
                state.value.image?.let { File(it.absolutePath).delete() }
                mutableState.update { it.copy(image = image, message = "Photo ready. Original capture time is unknown.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = "Could not read that photo. Select a local JPEG or PNG.") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun describe() {
        val image = state.value.image ?: return
        if (state.value.busy || !state.value.modelReady) return
        val context = RequestContext(sessionId, UUID.randomUUID().toString(), "bootstrap-smoke-test", 0, image.id)
        gate.begin(context)
        mutableState.update { it.copy(busy = true, canCancel = true, result = null, message = "Initializing the local model and describing the photo…") }
        inferenceJob = viewModelScope.launch {
            try {
                vision.describe(context, image).collect { response ->
                    if (!gate.accepts(response.context)) return@collect
                    when (response) {
                        is InferenceState.Loading -> Unit
                        is InferenceState.Success -> {
                            gate.consume(response.context)
                            mutableState.update { it.copy(result = response.value, message = "Local model result · selected photo") }
                        }
                        is InferenceState.Failure -> {
                            gate.consume(response.context)
                            mutableState.update { it.copy(message = "${response.kind}: ${response.message}") }
                        }
                    }
                }
            } finally {
                gate.invalidate()
                mutableState.update { it.copy(busy = false, canCancel = false) }
            }
        }
    }

    fun cancel() {
        gate.invalidate()
        mutableState.update { it.copy(result = null, canCancel = false, message = "Cancelled. Waiting for local runtime cleanup if needed.") }
        inferenceJob?.cancel()
    }
}
