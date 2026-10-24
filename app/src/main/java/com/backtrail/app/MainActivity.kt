package com.backtrail.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.backtrail.app.contracts.*
import com.backtrail.app.device.*
import com.backtrail.app.ui.*

class MainActivity:ComponentActivity() {
    private lateinit var heading:CameraHeading
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge();heading=CameraHeading(this)
        setContent {
            val model:TravisViewModel=viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshPermissions() }
            val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->uri?.let(model::importModel) }
            var pending by remember { mutableStateOf<TravisAction?>(null) }
            val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                model.refreshPermissions();pending?.let(model::action);pending=null
            }
            val act:(TravisAction)->Unit={ action->
                when {
                    action==TravisAction.ImportModel -> importer.launch(arrayOf("*/*"))
                    action==TravisAction.RequestPermissions || (action==TravisAction.Start && !state.locationAllowed && !state.replay) || (action==TravisAction.Capture && !state.cameraAllowed && !state.replay) -> {
                        pending=if(action==TravisAction.RequestPermissions)null else action
                        permissions.launch(buildList { add(Manifest.permission.ACCESS_FINE_LOCATION);add(Manifest.permission.ACCESS_COARSE_LOCATION);add(Manifest.permission.CAMERA);if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray())
                    }
                    else -> model.action(action)
                }
            }
            LaunchedEffect(Unit) { if(intent.getBooleanExtra("explore",false)) model.action(TravisAction.Explore) }
            TravisScreen(state,act,camera={ modifier->NativeCamera(model.camera,modifier) { model.announce(it) } },map={ modifier->OfflineTrailMap(state,modifier) })
        }
    }
    override fun onResume() { super.onResume();if(::heading.isInitialized)heading.start() }
    override fun onPause() { if(::heading.isInitialized)heading.stop();super.onPause() }
}
