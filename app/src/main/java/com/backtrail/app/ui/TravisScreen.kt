package com.backtrail.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.backtrail.app.contracts.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val TravisGreen = Color(0xFF173F35)
internal val TravisOrange = Color(0xFFE87742)
internal val TravisBlue = Color(0xFF3279D5)
internal val TravisIvory = Color(0xFFF4F2EA)
private enum class TravisSheet { ASK, MEMORIES, PREPARE, REPLAY }

/** UI only: all route, sensor, persistence and inference decisions belong to the supplied adapter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravisScreen(
    state: TravisUiState,
    onAction: (TravisAction) -> Unit,
    camera: @Composable (Modifier) -> Unit,
    map: @Composable (Modifier) -> Unit = { TravisMap(state, it) },
) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) darkColorScheme(
        primary = Color(0xFFB7D5BF), onPrimary = TravisGreen,
        background = Color(0xFF14231E), surface = Color(0xFF1C3028),
        surfaceContainer = Color(0xFF263B32), onSurface = TravisIvory,
        onSurfaceVariant = Color(0xFFB9C8BE), outlineVariant = Color(0xFF45584C),
        secondary = TravisOrange,
    ) else lightColorScheme(
        primary = TravisGreen, onPrimary = Color.White,
        background = TravisIvory, surface = TravisIvory,
        surfaceContainer = Color(0xFFE9EADF), onSurface = Color(0xFF19382E),
        onSurfaceVariant = Color(0xFF5C6C61), outlineVariant = Color(0xFFCFD7C9),
        secondary = Color(0xFFAD4D20),
    )
    MaterialTheme(colorScheme = palette) {
        var sheet by rememberSaveable { mutableStateOf<TravisSheet?>(null) }
        val haptic = LocalHapticFeedback.current
        val act: (TravisAction) -> Unit = { action ->
            if (action is TravisAction.Confirm || action is TravisAction.SavePhoto || action is TravisAction.Start) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            onAction(action)
        }
        BackHandler(enabled = state.captureMode || sheet != null || state.surface == TravisSurface.AR) {
            when {
                state.captureMode -> act(TravisAction.CancelCapture)
                sheet != null -> sheet = null
                else -> act(TravisAction.SwitchSurface(TravisSurface.MAP))
            }
        }
        Surface(Modifier.fillMaxSize(), color = palette.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TravisGlyph("trail", Modifier.size(31.dp), palette.primary)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("travis", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp)
                        Text("A LITTLE FURTHER. A WAY BACK.", fontSize = 8.sp, letterSpacing = 1.35.sp, color = palette.onSurfaceVariant)
                    }
                    IconButton(onClick = { sheet = TravisSheet.PREPARE }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Prepare and settings" }) {
                        TravisGlyph("settings", Modifier.size(23.dp), palette.onSurface)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val dockMaxHeight = maxHeight * .53f
                    if (state.surface == TravisSurface.MAP) map(Modifier.fillMaxSize())
                    else if (!state.captureMode) TravisCameraGuidance(state, camera, Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize().background(TravisGreen))

                    Column(Modifier.align(Alignment.TopCenter).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                            Surface(shape = RoundedCornerShape(20.dp), color = palette.surface, shadowElevation = 2.dp) {
                                Row(Modifier.padding(4.dp)) {
                                    listOf(TravisSurface.MAP to "Map", TravisSurface.AR to "AR").forEach { (surface, title) ->
                                        val selected = state.surface == surface
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = if (selected) palette.primary else Color.Transparent,
                                            onClick = { act(TravisAction.SwitchSurface(surface)) },
                                        ) {
                                            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                                TravisGlyph(if (surface == TravisSurface.MAP) "map" else "ar", Modifier.size(18.dp), if (selected) palette.onPrimary else palette.onSurface)
                                                Text(title, fontWeight = FontWeight.SemiBold, color = if (selected) palette.onPrimary else palette.onSurface)
                                            }
                                        }
                                    }
                                }
                            }
                            Surface(shape = CircleShape, color = palette.surface, shadowElevation = 2.dp) {
                                Column(Modifier.size(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text("N", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    TravisGlyph("north", Modifier.size(18.dp), TravisOrange)
                                }
                            }
                        }
                        if (state.replay) {
                            Surface(onClick = { sheet = TravisSheet.REPLAY }, shape = RoundedCornerShape(10.dp), color = Color(0xFFFFE3B6)) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("REPLAY", fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Color(0xFF673D0C))
                                    Text("  Sample movement · controls", fontSize = 11.sp, color = Color(0xFF673D0C))
                                }
                            }
                        } else if (state.fixQuality != FixQuality.USABLE) {
                            QualityLabel(state)
                        }
                    }

                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.notice.isNotBlank()) {
                            Surface(color = palette.surface, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp) {
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(state.notice, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = { act(TravisAction.DismissNotice) }) { Text("OK") }
                                }
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(26.dp), color = palette.surface,
                            shadowElevation = 8.dp, border = BorderStroke(1.dp, palette.outlineVariant.copy(alpha = .55f)),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().heightIn(max = dockMaxHeight).verticalScroll(rememberScrollState()).padding(17.dp).animateContentSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                DockContent(state, act, onAsk = { sheet = TravisSheet.ASK }, onMemories = { sheet = TravisSheet.MEMORIES }, onPrepare = { sheet = TravisSheet.PREPARE })
                            }
                        }
                    }
                }
            }
        }

        if (state.captureMode) {
            ModalBottomSheet(onDismissRequest = { if (!state.captureBusy) act(TravisAction.CancelCapture) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = palette.surface) {
                CaptureSheet(state, act, camera)
            }
        } else sheet?.let { active ->
            ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = palette.surface) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (active) {
                        TravisSheet.ASK -> AskSheet(state, act) { sheet = null }
                        TravisSheet.MEMORIES -> MemoriesSheet(state, act) { sheet = null }
                        TravisSheet.PREPARE -> PrepareSheet(state, act) { sheet = null }
                        TravisSheet.REPLAY -> ReplaySheet(state, act) { sheet = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockContent(state: TravisUiState, act: (TravisAction) -> Unit, onAsk: () -> Unit, onMemories: () -> Unit, onPrepare: () -> Unit) {
    val outing = state.outing
    val palette = MaterialTheme.colorScheme
    when {
        state.mode != GuidanceMode.NONE -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.targetPhoto.isNotBlank()) TravisPhoto(state.targetPhoto, "Original saved landmark", Modifier.size(62.dp).clip(RoundedCornerShape(13.dp)))
                Column(Modifier.weight(1f)) {
                    Eyebrow(if (state.mode == GuidanceMode.RETRACE) "RETRACING YOUR STEPS" else "FINDING A SAVED PLACE")
                    Text(state.targetTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (state.mode == GuidanceMode.RETRACE) "${distanceLabel(state.remainingMeters)} along recorded route"
                        else "${distanceLabel(state.distanceMeters)} straight-line distance",
                        style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant,
                    )
                }
            }
            if (state.mode == GuidanceMode.FIND_LANDMARK && outing.retraceIndex != null) {
                Text("Your retrace progress is saved.", style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
            }
            if (state.canConfirm) {
                Text("Near the recorded location. Compare the original photo before confirming.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryAction(if (state.mode == GuidanceMode.RETRACE) "I recognize this" else "Found this place", "check", Modifier.weight(1f), state.canConfirm) { act(TravisAction.Confirm) }
                QuietAction("Undo", "undo", Modifier.widthIn(min = 72.dp)) { act(TravisAction.Undo) }
            }
            val memory = outing.memories.firstOrNull { it.id == state.selectedMemoryId }
            if (memory != null && memory.pickup !in listOf(PickupPhase.NONE, PickupPhase.COLLECTED)) {
                OutlinedButton(onClick = { act(TravisAction.Collect(memory.id)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("I collected it") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onAsk) { Text("Ask Travis") }
                if (state.mode == GuidanceMode.FIND_LANDMARK && outing.retraceIndex != null) TextButton(onClick = { act(TravisAction.ResumeRetrace) }) { Text("Resume retrace") }
                else TextButton(onClick = onMemories) { Text("Saved places") }
                if (state.replay) TextButton(onClick = { act(TravisAction.ReplayNext) }) { Text("Next step") }
            }
        }
        outing.phase == OutingPhase.READY && outing.points.isEmpty() -> {
            Eyebrow("YOUR OFFLINE FIELD COMPANION")
            Text("Go a little further.", fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp)
            Text("Keep your trail. Remember the places. Find your way back.", style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallStatus(if (state.mapAvailable) "Map saved" else "No local map", state.mapAvailable)
                SmallStatus(if (state.modelReady) "Gemma ready" else "Gemma needed", state.modelReady)
            }
            PrimaryAction("Start outing", "trail", Modifier.fillMaxWidth()) { act(TravisAction.Start) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { act(TravisAction.Explore) }) { Text("Explore the demo  →") }
                TextButton(onClick = onPrepare) { Text("Prepare") }
            }
        }
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow(when (outing.phase) {
                        OutingPhase.RECORDING -> if (state.replay) "EXPLORING · SAMPLE OUTING" else "RECORDING YOUR OUTING"
                        OutingPhase.PAUSED -> "RECORDING PAUSED"
                        OutingPhase.ENDED -> "OUTING SAVED"
                        else -> "YOUR RECORDED TRAIL"
                    })
                    Text(outing.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${outing.points.size} recorded fixes · ${outing.memories.size} saved places", style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                }
                if (outing.phase == OutingPhase.RECORDING) {
                    IconButton(onClick = { act(TravisAction.Pause) }, modifier = Modifier.semantics { contentDescription = "Pause recording" }) { TravisGlyph("pause", Modifier.size(22.dp), palette.primary) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryAction(if (outing.phase == OutingPhase.PAUSED) "Resume outing" else "Save a place", if (outing.phase == OutingPhase.PAUSED) "play" else "camera", Modifier.weight(1f)) {
                    act(if (outing.phase == OutingPhase.PAUSED) TravisAction.Start else TravisAction.Capture)
                }
                QuietAction("Ask", "spark", Modifier.widthIn(min = 86.dp), onClick = onAsk)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { act(TravisAction.HeadBack) }, enabled = outing.points.size > 1) { TravisGlyph("back", Modifier.size(16.dp), palette.primary); Spacer(Modifier.width(6.dp)); Text("Head back") }
                TextButton(onClick = onMemories) { Text("Places") }
                if (state.replay) TextButton(onClick = { act(TravisAction.ReplayNext) }) { Text("Next step") }
                else if (outing.phase != OutingPhase.ENDED) TextButton(onClick = { act(TravisAction.End) }) { Text("End") }
            }
        }
    }
}

@Composable
private fun CaptureSheet(state: TravisUiState, act: (TravisAction) -> Unit, camera: @Composable (Modifier) -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    var pickup by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Eyebrow("SAVE SOMETHING WORTH RETURNING TO")
        Text("Remember this place.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth().height(205.dp).clip(RoundedCornerShape(20.dp)).background(TravisGreen)) {
            if (state.replay) {
                TravisPhoto(state.outing.memories.lastOrNull()?.photoPath.orEmpty(), "Sample landmark preview", Modifier.fillMaxSize())
                Text("SAMPLE CAMERA PREVIEW", Modifier.align(Alignment.BottomStart).padding(12.dp).background(TravisGreen).padding(6.dp), color = Color.White, fontSize = 10.sp, letterSpacing = 1.sp)
            } else if (state.cameraAllowed) camera(Modifier.fillMaxSize())
            else Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera access needed", color = Color.White)
                TextButton(onClick = { act(TravisAction.RequestPermissions) }) { Text("Allow camera", color = Color.White) }
            }
            Surface(Modifier.align(Alignment.TopEnd).padding(12.dp), shape = CircleShape, color = Color.Black.copy(alpha = .5f)) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { TravisGlyph("camera", Modifier.size(22.dp), Color.White) }
            }
        }
        Text(if (state.fixQuality == FixQuality.USABLE) "Photo and current measured location will be saved together." else "Photo will be saved. A reliable location is not available yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = note, onValueChange = { note = it.take(2000) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Your note · optional") }, placeholder = { Text("My jacket is on the wooden bench…") },
            minLines = 2, maxLines = 5, shape = RoundedCornerShape(15.dp), enabled = !state.captureBusy,
        )
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !state.captureBusy) { pickup = !pickup }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pickup, onCheckedChange = { pickup = it }, enabled = !state.captureBusy)
            Column(Modifier.weight(1f)) {
                Text("Remind me on the way back", fontWeight = FontWeight.Medium)
                Text("An opted-in local pickup reminder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.captureBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        PrimaryAction(if (state.captureBusy) "Saving original photo…" else "Save photo & place", "camera", Modifier.fillMaxWidth(), !state.captureBusy && (state.cameraAllowed || state.replay)) { act(TravisAction.SavePhoto(note, pickup)) }
        Text("The original stays on your phone. Gemma can add a description afterward.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AskSheet(state: TravisUiState, act: (TravisAction) -> Unit, close: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Eyebrow(if (state.replay) "EXPLORE · SAMPLE ANSWERS" else "GEMMA · ON THIS PHONE")
    Text("What are you looking for?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text("Search the places and details you saved on this outing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.outing.memories.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.outing.memories.take(5).forEach { memory ->
            Column(Modifier.width(84.dp)) {
                TravisPhoto(memory.photoPath, memory.title, Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)))
                Text(memory.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
    OutlinedTextField(
        value = query, onValueChange = { query = it.take(500) }, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Where did I leave my jacket?") }, label = { Text("Ask about your outing") },
        shape = RoundedCornerShape(16.dp), maxLines = 3,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank() && !state.busy) act(TravisAction.Ask(query)) }),
    )
    if (!state.modelReady && !state.replay) {
        Text("Gemma is not imported yet. Your route and saved photos remain available.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { act(TravisAction.ImportModel) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Import local Gemma model") }
    }
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.replay) "Sample loading state" else "Reading your saved memories…", Modifier.weight(1f))
            TextButton(onClick = { act(TravisAction.CancelInference) }) { Text("Cancel") }
        }
    } else PrimaryAction("Find in my memories", "spark", Modifier.fillMaxWidth(), query.isNotBlank() && (state.modelReady || state.replay)) { act(TravisAction.Ask(query)) }
    if (state.answer.isNotBlank()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(17.dp)) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Eyebrow(if (state.replay) "SAMPLE RESPONSE" else "FROM YOUR SAVED RECORDS")
                Text(state.answer, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    state.resultIds.mapNotNull { id -> state.outing.memories.firstOrNull { it.id == id } }.forEach { memory ->
        MemoryCard(memory, state.replay, onClick = { act(TravisAction.SelectMemory(memory.id)); act(TravisAction.SwitchSurface(TravisSurface.AR)); close() }, actionLabel = "Find in AR")
    }
    if (state.answer.isNotBlank() && state.resultIds.isEmpty() && !state.busy) {
        Text("No selected place. Browse your original saved photos to choose one yourself.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MemoriesSheet(state: TravisUiState, act: (TravisAction) -> Unit, close: () -> Unit) {
    Eyebrow("YOUR OUTING · ${state.outing.memories.size} PLACES")
    Text("Worth remembering.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    if (state.outing.memories.isEmpty()) {
        Text("Save a photo at a junction, a favorite view, or something you want to collect later.")
        PrimaryAction("Save your first place", "camera", Modifier.fillMaxWidth()) { close(); act(TravisAction.Capture) }
    }
    state.outing.memories.reversed().forEach { memory ->
        MemoryCard(memory, state.replay, onClick = { act(TravisAction.SelectMemory(memory.id)); act(TravisAction.SwitchSurface(TravisSurface.AR)); close() }, actionLabel = "Find in AR")
    }
}

@Composable
private fun MemoryCard(memory: Memory, replay: Boolean, onClick: () -> Unit, actionLabel: String) {
    Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Column {
            TravisPhoto(memory.photoPath, "Original saved photo of ${memory.title}", Modifier.fillMaxWidth().height(150.dp))
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(memory.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (replay) "SAMPLE" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(memory.capturedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (memory.note.isNotBlank()) Text("Your note: ${memory.note}", style = MaterialTheme.typography.bodyMedium)
                if (memory.description.isNotBlank()) Text("${if (replay) "Sample description" else "Gemma observation"}: ${memory.description}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(memory.indexing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (memory.point == null) Text("Location not recorded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (memory.pickup != PickupPhase.NONE) Text("Pickup · ${memory.pickup.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelMedium)
                PrimaryAction(actionLabel, "ar", Modifier.fillMaxWidth(), memory.point != null, onClick)
            }
        }
    }
}

@Composable
private fun PrepareSheet(state: TravisUiState, act: (TravisAction) -> Unit, close: () -> Unit) {
    Eyebrow("PACK THE ESSENTIALS")
    Text("Ready for off-grid.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    ReadinessRow("Location", if (state.locationAllowed) "Permission allowed · ${state.fixQuality.name.lowercase()} fix" else "Allow location to record an outing", state.locationAllowed)
    ReadinessRow("Offline map", if (state.mapAvailable) "Prepared local region available" else "No coverage here · recorded trace still works", state.mapAvailable)
    ReadinessRow("Gemma 4", if (state.modelReady) "Local model imported" else "Import verified model from phone storage", state.modelReady)
    ReadinessRow("Camera guidance", if (state.cameraAllowed) "Camera allowed · approximate sensor direction" else "Camera permission needed", state.cameraAllowed)
    ReadinessRow("Local reminders", if (state.notificationsAllowed) "Notifications allowed" else "Notification permission needed", state.notificationsAllowed)
    PrimaryAction("Allow device permissions", "settings", Modifier.fillMaxWidth()) { act(TravisAction.RequestPermissions) }
    OutlinedButton(onClick = { act(TravisAction.ImportModel) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.modelReady) "Replace local Gemma model" else "Import local Gemma model") }
    Text("Approximate camera guidance uses your location and camera orientation. It does not place a surveyed route on the ground.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.outing.phase != OutingPhase.READY) {
        OutlinedButton(onClick = { act(TravisAction.SetTimeReminder(15)); close() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Remind me in 15 minutes") }
    }
    if (state.savedOutings.isNotEmpty()) {
        HorizontalDivider()
        Text("Saved outings", style = MaterialTheme.typography.titleMedium)
        state.savedOutings.forEach { outing ->
            Surface(onClick = { act(TravisAction.OpenOuting(outing.id)); close() }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(outing.name, fontWeight = FontWeight.Medium); Text("${outing.points.size} fixes · ${outing.memories.size} places", style = MaterialTheme.typography.bodySmall) }
                    Text("→")
                }
            }
        }
    }
    TextButton(onClick = { act(if (state.replay) TravisAction.ExitExplore else TravisAction.Explore); close() }, modifier = Modifier.fillMaxWidth()) { Text(if (state.replay) "Leave Explore demo" else "Explore the interactive demo") }
}

@Composable
private fun ReplaySheet(state: TravisUiState, act: (TravisAction) -> Unit, close: () -> Unit) {
    Eyebrow("EXPLORE · REPLAY CONTROLS")
    Text("Try the journey.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text("Movement, camera previews and Gemma answers are samples. They are separate from your real outings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    PrimaryAction("Next route step · ${state.replayStep + 1}", "play", Modifier.fillMaxWidth()) { act(TravisAction.ReplayNext) }
    Text("Preview camera direction", style = MaterialTheme.typography.titleSmall)
    Slider(value = (state.heading ?: 0.0).toFloat(), onValueChange = { act(TravisAction.SetReplayHeading(it.toDouble())) }, valueRange = 0f..359f, modifier = Modifier.semantics { contentDescription = "Replay camera heading in degrees" })
    Text("${(state.heading ?: 0.0).toInt()}° · replay heading", style = MaterialTheme.typography.labelSmall)
    Text("Explore conditions", style = MaterialTheme.typography.titleMedium)
    listOf("normal" to "Normal", "weak_gps" to "Weak GPS", "missing_maps" to "Missing maps", "loading" to "Model loading", "error" to "Model error", "no_match" to "No match").chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { (issue, title) -> OutlinedButton(onClick = { act(TravisAction.DemoIssue(issue)); close() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(title) } }
        }
    }
    TextButton(onClick = { act(TravisAction.ExitExplore); close() }, modifier = Modifier.fillMaxWidth()) { Text("Exit demo · return to my outing") }
}

@Composable
private fun ReadinessRow(title: String, detail: String, ready: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(if (ready) MaterialTheme.colorScheme.surfaceContainer else TravisOrange.copy(alpha = .15f), CircleShape), contentAlignment = Alignment.Center) { TravisGlyph(if (ready) "check" else "info", Modifier.size(18.dp), MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun QualityLabel(state: TravisUiState) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
        Text(when (state.fixQuality) {
            FixQuality.USABLE -> "GPS ±${state.fix?.accuracy?.toInt() ?: 0} m"
            FixQuality.APPROXIMATE -> "Weak GPS · position approximate"
            FixQuality.STALE -> "Location stale · guidance paused"
            FixQuality.UNAVAILABLE -> "Waiting for a measured location"
        }, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SmallStatus(label: String, ready: Boolean) {
    Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(if (ready) MaterialTheme.colorScheme.primary else TravisOrange, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun Eyebrow(text: String) {
    Text(text, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PrimaryAction(label: String, icon: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 51.dp), enabled = enabled, shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        TravisGlyph(icon, Modifier.size(19.dp), if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        Spacer(Modifier.width(9.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun QuietAction(label: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 51.dp), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 13.dp)) {
        TravisGlyph(icon, Modifier.size(19.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp)); Text(label)
    }
}

internal fun distanceLabel(distance: Double?): String = when {
    distance == null || !distance.isFinite() -> "Distance unavailable"
    distance >= 1000 -> String.format(Locale.getDefault(), "%.1f km", distance / 1000)
    else -> "${distance.toInt()} m"
}
