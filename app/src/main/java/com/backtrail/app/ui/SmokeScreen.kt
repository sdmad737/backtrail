package com.backtrail.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.backtrail.app.BacktrailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class LocalDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
fun SmokeScreen(model: BacktrailViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val modelPicker = rememberLauncherForActivityResult(LocalDocument()) { uri: Uri? -> uri?.let(model::importModel) }
    val photoPicker = rememberLauncherForActivityResult(LocalDocument()) { uri: Uri? -> uri?.let(model::importPhoto) }
    val photo by produceState<ImageBitmap?>(null, state.image?.id) {
        value = null
        value = withContext(Dispatchers.IO) {
            state.image?.absolutePath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF315D46))) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Backtrail", style = MaterialTheme.typography.headlineLarge)
                Text("Remember the way back.", style = MaterialTheme.typography.titleMedium)
                Text("Bootstrap · local image smoke test", color = MaterialTheme.colorScheme.primary)
                Text("Route recording, camera capture and checkpoint comparison are not available in this starter.")
                HorizontalDivider()
                Text(if (state.modelReady) "Gemma model imported and verified" else "Local model unavailable", style = MaterialTheme.typography.titleMedium)
                Text("Import gemma-4-E2B-it.litertlm from this phone. This app does not download models.")
                OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                    Text(if (state.modelReady) "Replace model" else "Import model")
                }
                OutlinedButton(onClick = { photoPicker.launch(arrayOf("image/jpeg", "image/png")) }, enabled = !state.busy) {
                    Text("Select a local photo")
                }
                photo?.let { Image(it, "Selected photo for local description", Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.Fit) }
                Button(onClick = model::describe, enabled = state.modelReady && state.image != null && !state.busy) {
                    Text("Describe on this phone")
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.canCancel) TextButton(onClick = model::cancel) { Text("Cancel") }
                Text(state.message)
                state.result?.let { result ->
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(result.label, style = MaterialTheme.typography.titleLarge)
                            result.fixedFeatures.forEach { Text("• $it") }
                            if (result.fixedFeatures.isEmpty()) Text("No distinctive fixed features reported.")
                            Text("Model suggestion. This does not confirm your location or advance a route.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
