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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

data class CameraTorchState(val available:Boolean=false,val enabled:Boolean=false,val changing:Boolean=false)

class CameraCaptureController {
    private val mutableTorch=MutableStateFlow(CameraTorchState())
    val torch=mutableTorch.asStateFlow()
    private var boundCamera:Camera?=null
    private var torchExecutor:Executor?=null
    private var torchObserver:Observer<Int>?=null
    internal fun attachCamera(camera:Camera,owner:LifecycleOwner,executor:Executor) {
        boundCamera?.let(::detachCamera)
        boundCamera=camera;torchExecutor=executor
        mutableTorch.value=CameraTorchState(available=camera.cameraInfo.hasFlashUnit())
        val observer=Observer<Int> { value ->
            if(boundCamera===camera)mutableTorch.value=mutableTorch.value.copy(enabled=value==TorchState.ON)
        }
        torchObserver=observer
        camera.cameraInfo.torchState.observe(owner,observer)
    }
    internal fun detachCamera(camera:Camera) {
        if(boundCamera!==camera)return
        torchObserver?.let { camera.cameraInfo.torchState.removeObserver(it) }
        runCatching { camera.cameraControl.enableTorch(false) }
        boundCamera=null;torchExecutor=null;torchObserver=null
        mutableTorch.value=CameraTorchState()
    }
    fun toggleTorch(onFailure:(String)->Unit) {
        val camera=boundCamera
        val executor=torchExecutor
        if(camera==null || executor==null || !mutableTorch.value.available) { onFailure("Flashlight is unavailable on this camera.");return }
        if(mutableTorch.value.changing)return
        val enabled=!mutableTorch.value.enabled
        mutableTorch.value=mutableTorch.value.copy(changing=true)
        try {
            val future=camera.cameraControl.enableTorch(enabled)
            future.addListener({
                if(boundCamera===camera) {
                    try { future.get() }
                    catch(_:Exception) { onFailure("Could not change the flashlight. Try again.") }
                    finally { mutableTorch.value=mutableTorch.value.copy(changing=false) }
                }
            },executor)
        } catch(_:Exception) { mutableTorch.value=mutableTorch.value.copy(changing=false);onFailure("Could not change the flashlight. Try again.") }
    }

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
    fun close() { boundCamera?.let(::detachCamera);scope.cancel();executor.shutdown() }
}

@Composable fun NativeCamera(controller:CameraCaptureController,modifier:Modifier=Modifier,onFailure:(String)->Unit={}) {
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    val preview=remember { PreviewView(context).apply { implementationMode=PreviewView.ImplementationMode.COMPATIBLE; scaleType=PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycle) {
        var active=true
        var provider:ProcessCameraProvider?=null
        var cameraPreview:Preview?=null
        var capture:ImageCapture?=null
        var bound:Camera?=null
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            if(active) try {
                provider=future.get()
                val rotation=preview.display?.rotation ?: android.view.Surface.ROTATION_0
                cameraPreview=Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                capture=ImageCapture.Builder().setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                bound=provider!!.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,cameraPreview,capture)
                controller.attachCamera(bound!!,lifecycle,context.mainExecutor)
                controller.capture=capture
            } catch(_:Exception) { onFailure("Live camera unavailable. Check permission and camera support.") }
        },context.mainExecutor)
        onDispose { active=false;bound?.let(controller::detachCamera);if(controller.capture===capture)controller.capture=null;cameraPreview?.let { provider?.unbind(it) };capture?.let { provider?.unbind(it) } }
    }
    AndroidView(factory={preview},modifier=modifier)
}
