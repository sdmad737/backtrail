package com.backtrail.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.backtrail.app.contracts.*
import com.backtrail.app.R
import com.backtrail.app.trip.ApproachFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.draw.rotate

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
    onHeadingActiveChanged: (Boolean) -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) darkColorScheme(
        primary = Color(0xFFB7D5BF), onPrimary = TravisGreen,
        background = Color(0xFF14231E), surface = Color(0xFF1C3028),
        surfaceContainer = Color(0xFF263B32), onSurface = TravisIvory,
        onSurfaceVariant = Color(0xFFB9C8BE), outlineVariant = Color(0xFF45584C),
        secondary = TravisOrange, secondaryContainer = Color(0xFF304E3F), onSecondaryContainer = TravisIvory,
    ) else lightColorScheme(
        primary = TravisGreen, onPrimary = Color.White,
        background = TravisIvory, surface = TravisIvory,
        surfaceContainer = Color(0xFFE9EADF), onSurface = Color(0xFF19382E),
        onSurfaceVariant = Color(0xFF5C6C61), outlineVariant = Color(0xFFCFD7C9),
        secondary = Color(0xFFAD4D20), secondaryContainer = Color(0xFFDFE8D9), onSecondaryContainer = TravisGreen,
    )
    MaterialTheme(colorScheme = palette) {
        var sheet by rememberSaveable { mutableStateOf<TravisSheet?>(null) }
        val haptic = LocalHapticFeedback.current
        val context=LocalContext.current
        val preferences=remember(context) { context.getSharedPreferences("travis-ui",0) }
        var approachHaptics by remember { mutableStateOf(preferences.getBoolean("approachHaptics",true)) }
        val lifecycle=LocalLifecycleOwner.current.lifecycle
        var visible by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { visible=true }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { visible=false }
        val liveCameraVisible=visible && !state.replay && state.cameraAllowed && state.surface==TravisSurface.AR && !state.captureMode && sheet==null
        val liveHeadingVisible=visible && !state.replay && !state.captureMode && sheet==null && state.fixQuality==FixQuality.USABLE && (state.surface==TravisSurface.MAP || liveCameraVisible)
        LaunchedEffect(liveHeadingVisible) { onHeadingActiveChanged(liveHeadingVisible) }
        DisposableEffect(Unit) { onDispose { onHeadingActiveChanged(false) } }
        val approach=remember { ApproachFeedback() }
        LaunchedEffect(state.outing.id,state.fix?.time,state.fixQuality,state.target?.id,state.mode,approachHaptics,visible,sheet,state.captureMode,state.replay) {
            val enabled=approachHaptics && visible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && sheet==null && !state.captureMode && !state.replay && state.mode!=GuidanceMode.NONE && state.fixQuality==FixQuality.USABLE
            if(approach.observe(state.target?.let { "${state.outing.id}:${it.id}" },state.distanceMeters,state.fix?.accuracy,state.fix?.time,System.currentTimeMillis(),enabled))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        var lastMemoryCount by remember(state.outing.id) { mutableIntStateOf(state.outing.memories.size) }
        LaunchedEffect(state.outing.memories.size) {
            if (visible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && state.outing.memories.size > lastMemoryCount) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            lastMemoryCount = state.outing.memories.size
        }
        val act: (TravisAction) -> Unit = { action ->
            if (action is TravisAction.Confirm || action is TravisAction.Collect || action is TravisAction.Start) {
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
                    TravisLogo(Modifier.size(42.dp))
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("travis", fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
                        Text("REMEMBER THE WAY BACK", fontSize = 9.sp, letterSpacing = .55.sp, color = palette.onSurfaceVariant)
                    }
                    IconButton(onClick = { sheet = TravisSheet.PREPARE }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Prepare and settings" }) {
                        TravisGlyph("settings", Modifier.size(23.dp), palette.onSurface)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val dockMaxHeight = maxHeight * if (state.surface == TravisSurface.AR) .46f else .53f
                    if (state.surface == TravisSurface.MAP) map(Modifier.fillMaxSize())
                    else if (!state.captureMode) TravisCameraGuidance(state, { cameraModifier ->
                        if(liveCameraVisible)camera(cameraModifier)else Box(cameraModifier.background(TravisGreen))
                    }, Modifier.fillMaxSize())
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
                            if (state.surface == TravisSurface.MAP) {
                                val heading = state.mapHeading.takeIf { state.mapHeadingReliable && state.fixQuality == FixQuality.USABLE }
                                Surface(shape = RoundedCornerShape(16.dp), color = palette.surface, shadowElevation = 2.dp,
                                    modifier = Modifier.semantics { contentDescription = if (heading == null) "Map is north up. Compass unavailable; use a fresh GPS fix and move away from magnetic interference." else "Map is north up. Approximate facing direction ${heading.toInt()} degrees." }) {
                                    Column(Modifier.widthIn(min = 67.dp).padding(horizontal = 8.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(if (heading == null) "COMPASS" else "NORTH UP", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                            if (heading != null) TravisGlyph("north", Modifier.size(17.dp).rotate(heading.toFloat()), TravisBlue)
                                            Text(if (heading == null) "Unavailable" else "≈${heading.toInt()}°", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (heading == null) palette.onSurfaceVariant else TravisBlue)
                                        }
                                    }
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
                        } else {
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

        state.notificationMemory?.let { memory ->
            AlertDialog(
                onDismissRequest = { act(TravisAction.DismissNotification) },
                title = { Text(memory.title, fontWeight = FontWeight.SemiBold) },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Eyebrow("REMINDER · ${state.notificationOutingName}")
                        TravisPhoto(memory.photoPath, "Original saved photo of ${memory.title}", Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)))
                        if (memory.note.isNotBlank()) {
                            Text("Your note", style = MaterialTheme.typography.labelMedium)
                            Text(memory.note, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Your current outing stays open. This is the saved place behind the reminder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = { act(TravisAction.DismissNotification) }) { Text("Dismiss") } },
            )
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
                        TravisSheet.PREPARE -> PrepareSheet(state,act,approachHaptics,{ enabled ->
                            approachHaptics=enabled;preferences.edit().putBoolean("approachHaptics",enabled).apply()
                        }) { sheet = null }
                        TravisSheet.REPLAY -> ReplaySheet(state, act) { sheet = null }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DockContent(state: TravisUiState, act: (TravisAction) -> Unit, onAsk: () -> Unit, onMemories: () -> Unit, onPrepare: () -> Unit) {
    val outing = state.outing
    val palette = MaterialTheme.colorScheme
    var guidanceTools by rememberSaveable(state.mode, state.target?.id) { mutableStateOf(false) }
    when {
        state.mode != GuidanceMode.NONE -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val referencePhoto = state.targetPhoto
                if (referencePhoto.isNotBlank()) TravisPhoto(referencePhoto, "Original photo of the next saved checkpoint", Modifier.size(62.dp).clip(RoundedCornerShape(13.dp)))
                Column(Modifier.weight(1f)) {
                    Eyebrow(if (state.mode == GuidanceMode.RETRACE) "RETRACING YOUR STEPS" else "FINDING A SAVED PLACE")
                    Text(state.targetTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (state.mode == GuidanceMode.RETRACE) (if(state.remainingMeters==null) "Route distance unavailable" else "~${distanceLabel(state.remainingMeters)} remaining via recorded route")
                        else if(state.distanceMeters==null) "Distance unavailable" else "${distanceLabel(state.distanceMeters)} straight-line distance",
                        style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant,
                    )
                }
            }
            if (state.mode == GuidanceMode.RETRACE) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.destinationPhoto.isNotBlank() && state.destinationPhoto != state.targetPhoto) TravisPhoto(state.destinationPhoto, "Final destination photo", Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)))
                    Text("Returning to ${state.destinationTitle} · ${if (state.distanceMeters == null) "next-point distance unavailable" else "next point ${distanceLabel(state.distanceMeters)} away"}", style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                }
            }
            if (state.guidanceStatus.isNotBlank()) Text(state.guidanceStatus, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
            if (state.mode == GuidanceMode.FIND_LANDMARK && state.canResumeRetrace) {
                Text("Your retrace progress is saved.", style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
            }
            if (state.canConfirm) {
                Text(if(state.targetPhoto.isBlank()) "Near the next recorded waypoint. Confirm to continue." else "Near the recorded location. Compare the original photo before confirming.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryAction(if (state.mode == GuidanceMode.RETRACE) (if(state.targetPhoto.isBlank()) "Confirm waypoint" else "I recognize this") else "Found this place", "check", Modifier.weight(1f), state.canConfirm) { act(TravisAction.Confirm) }
                if (state.mode == GuidanceMode.RETRACE) QuietAction("Undo", "undo", Modifier.widthIn(min = 72.dp)) { act(TravisAction.Undo) }
            }
            val memory = when (state.mode) {
                GuidanceMode.FIND_LANDMARK -> outing.memories.firstOrNull { it.id == state.selectedMemoryId }
                else -> state.routeTargetIndex?.let { expectedIndex -> outing.memories.firstOrNull { it.routeIndex == expectedIndex } }
            }
            if (guidanceTools && memory != null) {
                OutlinedButton(onClick={act(TravisAction.CompareCurrentPhoto(memory.id))},enabled=!state.busy && (state.modelReady || state.replay) && (state.cameraAllowed || state.replay),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text(if(state.comparisonVerdict==null)"Compare with a current photo" else "Take another comparison photo")
                }
                when(state.comparisonVerdict) {
                    "SUPPORTS_MATCH" -> Surface(color=palette.surfaceContainer,shape=RoundedCornerShape(14.dp)) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text("This looks like the same spot.",fontWeight=FontWeight.SemiBold)
                        state.comparisonReasons.forEach { Text("• $it",style=MaterialTheme.typography.bodySmall) }
                        Text("Gemma's comparison is supporting evidence, not a location fix.",style=MaterialTheme.typography.labelSmall,color=palette.onSurfaceVariant)
                    } }
                    "DIFFERENT_PLACE","INSUFFICIENT_EVIDENCE" -> Surface(color=palette.surfaceContainer,shape=RoundedCornerShape(14.dp)) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text("Not sure this is the same spot.",fontWeight=FontWeight.SemiBold)
                        Text("Try another photo, or use the saved photo and your own judgment.",style=MaterialTheme.typography.bodySmall,color=palette.onSurfaceVariant)
                    } }
                    "ERROR" -> Text(state.inferenceMessage,style=MaterialTheme.typography.bodySmall,color=palette.onSurfaceVariant)
                }
            }
            if(guidanceTools && state.mode==GuidanceMode.RETRACE) {
                OutlinedButton(onClick={act(TravisAction.CheckDirection)},enabled=state.routeTargetIndex!=null,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text("Am I going the right way?")
                }
                state.rightWayResult?.let { result ->
                    Surface(color=palette.surfaceContainer,shape=RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(result.summary,fontWeight=FontWeight.Medium)
                            result.reasons.forEach { reason ->
                                Text("${reason.source.name.lowercase().replaceFirstChar { it.uppercase() }} · ${reason.text}",style=MaterialTheme.typography.bodySmall,color=palette.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (memory != null && memory.pickup !in listOf(PickupPhase.NONE, PickupPhase.COLLECTED)) {
                OutlinedButton(onClick = { act(TravisAction.Collect(memory.id)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("I collected it") }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAsk) { Text("Ask Travis") }
                if (state.mode == GuidanceMode.FIND_LANDMARK && state.canResumeRetrace) TextButton(onClick = { act(TravisAction.ResumeRetrace) }) { Text("Resume retrace") }
                else TextButton(onClick = onMemories) { Text("Saved places") }
                if (state.replay) TextButton(onClick = { act(TravisAction.ReplayNext) }) { Text("Next step") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { guidanceTools = !guidanceTools }) { Text(if (guidanceTools) "Fewer tools" else "Guidance tools") }
                TextButton(onClick = { act(TravisAction.StopGuidance) }) { Text("Close guidance") }
            }
        }
        outing.phase == OutingPhase.READY && outing.points.isEmpty() -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TravisLogo(Modifier.width(62.dp).height(56.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Eyebrow("YOUR OFFLINE FIELD COMPANION")
                    Text("Go a little further.", fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp)
                }
            }
            Text("Keep your trail. Remember the places. Find your way back.", style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallStatus(if (state.mapAvailable) "Map coverage ready" else "Check map coverage", state.mapAvailable)
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
            if (state.canResumeRetrace) {
                OutlinedButton(onClick = { act(TravisAction.ResumeRetrace) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Resume saved retrace") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryAction(when (outing.phase) { OutingPhase.PAUSED -> "Resume outing"; OutingPhase.ENDED -> "Review saved places"; else -> "Save a place" }, if (outing.phase == OutingPhase.PAUSED) "play" else "camera", Modifier.weight(1f)) {
                    if (outing.phase == OutingPhase.ENDED) onMemories() else act(if (outing.phase == OutingPhase.PAUSED) TravisAction.Start else TravisAction.Capture)
                }
                QuietAction("Ask", "spark", Modifier.widthIn(min = 86.dp), onClick = onAsk)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { act(TravisAction.HeadBack) }, enabled = outing.points.size > 1) { TravisGlyph("back", Modifier.size(16.dp), palette.primary); Spacer(Modifier.width(6.dp)); Text("Head back") }
                TextButton(onClick = onMemories) { Text("Places") }
                if (state.replay) TextButton(onClick = { act(TravisAction.ReplayNext) }) { Text("Next step") }
                else if (outing.phase != OutingPhase.ENDED) TextButton(onClick = { act(TravisAction.End) }) { Text("End") }
                else TextButton(onClick = { act(TravisAction.Start) }) { Text("New outing") }
            }
        }
    }
}

@Composable
internal fun TravisLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.travis_logo_transparent),
        contentDescription = "Travis trail logo",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun CaptureSheet(state: TravisUiState, act: (TravisAction) -> Unit, camera: @Composable (Modifier) -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    var pickup by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.notice.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
                Text(state.notice, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
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
    val keyboard = LocalSoftwareKeyboardController.current
    val ask: (TravisAction) -> Unit = { keyboard?.hide(); act(it) }
    var mode by rememberSaveable { mutableStateOf(state.assistantMode) }
    var selectedPhotoId by rememberSaveable { mutableStateOf(state.outing.memories.lastOrNull()?.id.orEmpty()) }
    Eyebrow(if (state.replay) "EXPLORE · SAMPLE ANSWERS" else "GEMMA · ON THIS PHONE")
    Text(if(mode==AssistantMode.FIND_PLACE) "What are you looking for?" else if(mode==AssistantMode.TRIP_QUESTION) "Ask about this outing." else "Ask about a saved photo.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(AssistantMode.FIND_PLACE to "Find a place",AssistantMode.TRIP_QUESTION to "Ask about trip",AssistantMode.PHOTO_QUESTION to "Ask about photo").forEach { (item,title)->
            FilterChip(selected=mode==item,onClick={mode=item},label={Text(title)},enabled=!state.busy)
        }
    }
    Text(when(mode){AssistantMode.FIND_PLACE->"Choose from the places and details you saved.";AssistantMode.TRIP_QUESTION->"Answers stay grounded in the recent places and notes from this outing.";AssistantMode.PHOTO_QUESTION->"Choose an original photo and ask about visible details. Your saved location stays unchanged."}, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (mode==AssistantMode.PHOTO_QUESTION && state.outing.memories.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.outing.memories.takeLast(8).forEach { memory ->
            val selected=selectedPhotoId==memory.id
            Surface(onClick={selectedPhotoId=memory.id},shape=RoundedCornerShape(13.dp),color=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,enabled=!state.busy) {
                Column(Modifier.width(84.dp).padding(6.dp)) {
                    TravisPhoto(memory.photoPath, "Saved photo of ${memory.title}", Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)))
                    Text(memory.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
    OutlinedTextField(
        value = query, onValueChange = { query = it.take(500) }, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(when(mode){AssistantMode.FIND_PLACE->"Where did I leave my jacket?";AssistantMode.TRIP_QUESTION->"What did I mean to collect?";AssistantMode.PHOTO_QUESTION->"What is the sign beside this path?"}) }, label = { Text("Your question") },
        shape = RoundedCornerShape(16.dp), maxLines = 3, enabled=!state.busy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank() && !state.busy) submitAssistant(mode,selectedPhotoId,query,ask) }),
    )
    if (!state.modelReady && !state.replay) {
        Text("Gemma is not imported yet. Your route and saved photos remain available.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { act(TravisAction.ImportModel) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Import local Gemma model") }
    }
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(state.inferenceMessage.ifBlank { if (state.replay) "Sample loading state" else "Working with saved places on this phone…" }, Modifier.weight(1f))
            TextButton(onClick = { act(TravisAction.CancelInference) }) { Text("Cancel") }
        }
    } else PrimaryAction(when(mode){AssistantMode.FIND_PLACE->"Find in my memories";AssistantMode.TRIP_QUESTION->"Ask about this outing";AssistantMode.PHOTO_QUESTION->"Ask about this photo"}, "spark", Modifier.fillMaxWidth(), query.isNotBlank() && (mode!=AssistantMode.PHOTO_QUESTION || selectedPhotoId.isNotBlank()) && (state.modelReady || state.replay)) { submitAssistant(mode,selectedPhotoId,query,ask) }
    if (state.answer.isNotBlank() && state.assistantMode==mode) {
        Surface(color = if(state.answerGrounded==false)MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(17.dp),border=if(state.answerGrounded==false)BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)else null) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Eyebrow(when { state.answerStatus==AssistantAnswerStatus.SAMPLE -> "SAMPLE RESPONSE";state.safetyRefusal -> "PHOTO SAFETY LIMIT";state.answerStatus==AssistantAnswerStatus.UNAVAILABLE -> "MODEL UNAVAILABLE";state.answerStatus==AssistantAnswerStatus.FAILURE -> "COULD NOT ANSWER";state.answerGrounded==false -> "NOT FOUND IN SAVED PLACES";state.answerConfidence!=null -> "PHOTO INTERPRETATION";state.answerStatus==AssistantAnswerStatus.LIVE -> "GEMMA 4 · LOCAL RESULT";else -> "SAVED PLACE STATUS" })
                Text(state.answer, style = MaterialTheme.typography.bodyMedium)
                if(state.answerConfidence!=null && !state.safetyRefusal) Text("Check the original photo before acting on this interpretation.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.inferenceMessage.isNotBlank()) Text(state.inferenceMessage,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if(state.assistantMode==mode) state.resultIds.mapNotNull { id -> state.outing.memories.firstOrNull { it.id == id } }.forEach { memory ->
        if(mode==AssistantMode.PHOTO_QUESTION) {
            TravisPhoto(memory.photoPath,"Original saved photo of ${memory.title}",Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)))
        } else MemoryCard(memory, state, onClick = { act(TravisAction.SelectMemory(memory.id)); act(TravisAction.SwitchSurface(TravisSurface.AR)); close() }, actionLabel = "Find in AR", act=act, onCloseMemoryCard=close)
    }
    if(mode==AssistantMode.FIND_PLACE && state.answerStatus==AssistantAnswerStatus.LIVE && state.resultIds.isNotEmpty()) {
        TextButton(onClick={act(TravisAction.DismissAssistantResult)}) { Text("Not it") }
    }
    if (state.answer.isNotBlank() && state.assistantMode==mode && state.resultIds.isEmpty() && !state.busy) {
        Text("No selected place. Browse your original saved photos to choose one yourself.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun submitAssistant(mode:AssistantMode,photoId:String,query:String,act:(TravisAction)->Unit) {
    when(mode) {
        AssistantMode.FIND_PLACE -> act(TravisAction.Ask(query))
        AssistantMode.TRIP_QUESTION -> act(TravisAction.AskAboutTrip(query))
        AssistantMode.PHOTO_QUESTION -> if(photoId.isNotBlank()) act(TravisAction.AskAboutPhoto(photoId,query))
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
        MemoryCard(memory, state, onClick = { act(TravisAction.SelectMemory(memory.id)); act(TravisAction.SwitchSurface(TravisSurface.AR)); close() }, actionLabel = "Find in AR",act=act, onCloseMemoryCard=close)
    }
}

@Composable
private fun MemoryCard(memory: Memory, state:TravisUiState, onClick: () -> Unit, actionLabel: String, act:(TravisAction)->Unit, onCloseMemoryCard: () -> Unit) {
    val replay=state.replay
    var editing by rememberSaveable(memory.id) { mutableStateOf(false) }
    var titleDraft by rememberSaveable(memory.id) { mutableStateOf(memory.title) }
    var noteDraft by rememberSaveable(memory.id) { mutableStateOf(memory.note) }
    var confirmDelete by rememberSaveable(memory.id) { mutableStateOf(false) }
    var featuresExpanded by rememberSaveable(memory.id) { mutableStateOf(false) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove this saved place?") },
        text = { Text("Its photo, note and reminders will be removed. The recorded route stays intact.") },
        confirmButton = { TextButton(onClick = { act(TravisAction.DeleteMemory(memory.id)); confirmDelete = false }) { Text("Remove place", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep place") } },
    )
    Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Column {
            TravisPhoto(memory.photoPath, "Original saved photo of ${memory.title}", Modifier.fillMaxWidth().height(150.dp))
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if(editing) {
                    OutlinedTextField(titleDraft,{titleDraft=it.take(80)},Modifier.fillMaxWidth(),label={Text("Place name")},singleLine=true)
                    OutlinedTextField(noteDraft,{noteDraft=it.take(2000)},Modifier.fillMaxWidth(),label={Text("Your original note")},minLines=2,maxLines=4)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={editing=false}) { Text("Cancel") }
                        Button(onClick={act(TravisAction.UpdateMemory(memory.id,titleDraft,memory.description,noteDraft));editing=false}) { Text("Save edits") }
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(memory.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (replay) "SAMPLE" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(memory.capturedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (memory.note.isNotBlank()) Text("Your note: ${memory.note}", style = MaterialTheme.typography.bodyMedium)
                if (memory.description.isNotBlank() && !editing) {
                    TextButton(onClick={featuresExpanded=!featuresExpanded},contentPadding=PaddingValues(0.dp)) { Text("${if (replay) "Sample description" else "Gemma 4 · local result"} · ${if(featuresExpanded)"hide details" else "show features"}") }
                    if(featuresExpanded) memory.description.split(" · ").filter(String::isNotBlank).forEach { Text("• $it",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                } else if(!editing) Text(if(state.describingMemoryId==memory.id)state.inferenceMessage else memory.indexing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (memory.point == null) Text("Location not recorded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (memory.pickup != PickupPhase.NONE) Text("${memory.pickupTask.ifBlank { "Pickup" }} · ${memory.pickup.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelMedium)
                if(!replay && memory.indexing.startsWith("Gemma 4") && memory.description.isBlank()) Text("Unclear view · add your own note or edit this place.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.retryDescriptionId==memory.id) {
                    Text("Photo preserved. ${state.inferenceMessage}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick={act(TravisAction.RetryDescription(memory.id))},enabled=!state.busy) { Text("Try description again") }
                }
                if(!replay && memory.note.isNotBlank() && memory.pickup==PickupPhase.NONE) TextButton(onClick={act(TravisAction.SuggestReminder(memory.id))},enabled=!state.busy) { Text("Check note for a return reminder") }
                if(!state.busy && state.reminderMessageMemoryId==memory.id && state.inferenceMessage.isNotBlank()) Text(state.inferenceMessage,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                statePendingReminder(memory,state.pendingReminder,act)
                if (!replay && !editing) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Alert when I arrive", style = MaterialTheme.typography.bodyMedium)
                            Text(if (memory.arrival == ArrivalPhase.NOTIFIED) "Already delivered" else "At this saved location", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = memory.arrival != ArrivalPhase.NONE, onCheckedChange = { act(TravisAction.SetArrivalReminder(memory.id, it)) }, enabled = memory.point != null)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { titleDraft = memory.title; noteDraft = memory.note; editing = true }) { Text("Edit name & note") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (memory.point != null && memory.routeIndex != null) {
                    PrimaryAction("Take me back", "back", Modifier.fillMaxWidth()) { act(TravisAction.TakeMeBack(memory.id)); act(TravisAction.SwitchSurface(TravisSurface.MAP)); onCloseMemoryCard() }
                    Text("Follow the recorded route to this place.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Find in AR · direct bearing") }
                } else PrimaryAction(actionLabel, "ar", Modifier.fillMaxWidth(), memory.point != null, onClick)
                if (memory.pickup !in listOf(PickupPhase.NONE, PickupPhase.COLLECTED)) OutlinedButton(onClick = { act(TravisAction.Collect(memory.id)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("I collected it") }
            }
        }
    }
}

@Composable
private fun statePendingReminder(memory:Memory,suggestion:ReminderSuggestion?,act:(TravisAction)->Unit) {
    if(suggestion?.memoryId==memory.id) Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Remind me on the way back: ${suggestion.task}",fontWeight=FontWeight.Medium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={act(TravisAction.ConfirmReminder)}) { Text("Add reminder") }
                TextButton(onClick={act(TravisAction.DismissReminder)}) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun PrepareSheet(state: TravisUiState, act: (TravisAction) -> Unit, approachHaptics:Boolean, onApproachHaptics:(Boolean)->Unit, close: () -> Unit) {
    Eyebrow("PACK THE ESSENTIALS")
    Text("Before you head out.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    ReadinessRow("Location", if (state.locationAllowed) "${state.fixQuality.name.lowercase().replaceFirstChar { it.uppercase() }} measured fix" else "Allow location to record an outing", state.fixQuality == FixQuality.USABLE)
    ReadinessRow("Offline map", if (state.mapAvailable) "Prepared local region available" else "No coverage here · recorded trace still works", state.mapAvailable)
    ReadinessRow("Gemma 4", if (state.modelReady) "Local model imported" else "Import verified model from phone storage", state.modelReady)
    ReadinessRow("Camera guidance", if (state.cameraAllowed) "Camera allowed · approximate sensor direction" else "Camera permission needed", state.cameraAllowed)
    ReadinessRow("Local reminders", if (state.notificationsAllowed) "Notifications allowed" else "Notification permission needed", state.notificationsAllowed)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Approach haptics",fontWeight=FontWeight.Medium)
            Text("A brief cue as good GPS fixes show you getting closer. No continuous buzzing.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked=approachHaptics,onCheckedChange=onApproachHaptics,modifier=Modifier.semantics { contentDescription="Approach haptics" })
    }
    Text("Camera pauses outside AR or capture. The compass pauses when the app is hidden. Gemma runs only when requested; active route recording continues with the screen locked.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
    var showProof by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showProof = !showProof }) { Text(if (showProof) "Hide device proof" else "Device & local model proof") }
    if (showProof) Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", fontWeight = FontWeight.SemiBold)
            Text(if (state.replay) "REPLAY · sample movement and answers" else "LIVE · measured device sensors", style = MaterialTheme.typography.bodySmall)
            Text("Backend: ${state.modelBackend}", style = MaterialTheme.typography.bodySmall)
            Text(state.modelFileName.ifBlank { "No local model selected" }, style = MaterialTheme.typography.bodySmall)
            Text(state.modelOutcome, style = MaterialTheme.typography.bodySmall)
            state.modelLoadMillis?.let { Text("Measured load: $it ms", style = MaterialTheme.typography.bodySmall) }
            state.modelGenerateMillis?.let { Text("Measured generation: $it ms", style = MaterialTheme.typography.bodySmall) }
            Text("Offline proof requires a cold launch with Wi-Fi and cellular data off.", style = MaterialTheme.typography.bodySmall)
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
        Text(state.sensorStatus.ifBlank { when (state.fixQuality) {
            FixQuality.USABLE -> "GPS ±${state.fix?.accuracy?.toInt() ?: 0} m · ${state.fix?.let { ((System.currentTimeMillis() - it.time).coerceAtLeast(0) / 1000) } ?: 0}s ago"
            FixQuality.APPROXIMATE -> "Weak GPS · position approximate"
            FixQuality.STALE -> "Location stale · guidance paused"
            FixQuality.UNAVAILABLE -> "Waiting for a measured location"
        } }, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
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
