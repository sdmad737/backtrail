package com.backtrail.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

class MainActivity:ComponentActivity() {
    private lateinit var heading:CameraHeading
    private var cameraVisible=false
    private val incomingDestination=MutableStateFlow<NotificationDestination?>(null)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge();heading=CameraHeading(this)
        if(savedInstanceState==null)incomingDestination.value=TravisNotifications.readDestination(intent)
        consumeReminderIntent(intent)
        setContent {
            val model:TravisViewModel=viewModel()
            LaunchedEffect(Unit) { incomingDestination.filterNotNull().collect { destination ->
                model.openNotification(destination);incomingDestination.value=null
            } }
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
            LaunchedEffect(Unit) { if(savedInstanceState==null && intent.getBooleanExtra("explore",false)) model.action(TravisAction.Explore);intent.removeExtra("explore") }
            TravisScreen(state,act,camera={ modifier->NativeCamera(model.camera,modifier) { model.announce(it) } },map={ modifier->OfflineTrailMap(state,modifier,onMemorySelected={ id->model.action(TravisAction.SelectMemory(id)) }) },
                onCameraActiveChanged={ visible ->
                    cameraVisible=visible
                    if(visible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))heading.start()else heading.stop()
                })
        }
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        incomingDestination.value=TravisNotifications.readDestination(intent)
        consumeReminderIntent(intent)
    }
    private fun consumeReminderIntent(intent:Intent) {
        intent.removeExtra(TravisNotifications.EXTRA_OUTING_ID)
        intent.removeExtra(TravisNotifications.EXTRA_MEMORY_ID)
        intent.removeExtra(TravisNotifications.EXTRA_REMINDER_KIND)
    }
    override fun onResume() { super.onResume();if(::heading.isInitialized && cameraVisible)heading.start() }
    override fun onPause() { if(::heading.isInitialized)heading.stop();super.onPause() }
}
