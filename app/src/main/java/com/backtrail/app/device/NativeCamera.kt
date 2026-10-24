package com.backtrail.app.device

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class CameraCaptureController {
    private val ready=MutableStateFlow<ImageCapture?>(null)
    internal var capture:ImageCapture?
        get()=ready.value
        set(value) { ready.value=value }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val executor=Executors.newSingleThreadExecutor()
    fun takePhoto(file:File,onCapture:()->Unit={},done:(Boolean)->Unit) {
        scope.launch {
            // The sheet can accept Save before the asynchronous provider binds.
            // Keep the existing saving indicator active, with a bounded wait.
            val useCase=withTimeoutOrNull(10_000) { ready.filterNotNull().first() }
            if(useCase==null) { done(false);return@launch }
            onCapture()
            useCase.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),executor,object:ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result:ImageCapture.OutputFileResults) { done(true) }
                override fun onError(error:ImageCaptureException) { done(false) }
            })
        }
    }
    fun close() { scope.cancel();executor.shutdown() }
}

@Composable fun NativeCamera(controller:CameraCaptureController,modifier:Modifier=Modifier,onFailure:(String)->Unit={}) {
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    val preview=remember { PreviewView(context).apply { implementationMode=PreviewView.ImplementationMode.COMPATIBLE; scaleType=PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycle) {
        var active=true
        var provider:ProcessCameraProvider?=null
        var cameraPreview:Preview?=null
        var capture:ImageCapture?=null
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            if(active) try {
                provider=future.get()
                val rotation=preview.display?.rotation ?: android.view.Surface.ROTATION_0
                cameraPreview=Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                capture=ImageCapture.Builder().setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider!!.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,cameraPreview,capture)
                controller.capture=capture
            } catch(_:Exception) { onFailure("Live camera unavailable. Check permission and camera support.") }
        },context.mainExecutor)
        onDispose { active=false;if(controller.capture===capture)controller.capture=null;cameraPreview?.let { provider?.unbind(it) };capture?.let { provider?.unbind(it) } }
    }
    AndroidView(factory={preview},modifier=modifier)
}
