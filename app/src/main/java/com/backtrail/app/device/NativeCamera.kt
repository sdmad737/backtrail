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

class CameraCaptureController {
    internal var capture:ImageCapture?=null
    private val executor=Executors.newSingleThreadExecutor()
    fun takePhoto(file:File,done:(Boolean)->Unit) {
        val useCase=capture ?: run { done(false);return }
        useCase.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),executor,object:ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result:ImageCapture.OutputFileResults) { done(true) }
            override fun onError(error:ImageCaptureException) { done(false) }
        })
    }
    fun close() { executor.shutdown() }
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
        onDispose { active=false;controller.capture=null;cameraPreview?.let { provider?.unbind(it) };capture?.let { provider?.unbind(it) } }
    }
    AndroidView(factory={preview},modifier=modifier)
}
