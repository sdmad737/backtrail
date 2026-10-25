package com.backtrail.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
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
import com.backtrail.app.trip.ReturnRoute
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
private enum class TravisSheet { ASK, MEMORIES, JOURNEYS, PREPARE, REPLAY }

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
                if (state.surface != TravisSurface.AR) Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TravisLogo(Modifier.size(34.dp))
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("travis", fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
                        Text("REMEMBER THE WAY BACK", fontSize = 9.sp, letterSpacing = .55.sp, color = palette.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { sheet = TravisSheet.JOURNEYS }) { Text("Journeys") }
                    IconButton(onClick = { sheet = TravisSheet.PREPARE }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Prepare and settings" }) {
                        TravisGlyph("settings", Modifier.size(23.dp), palette.onSurface)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val dockMaxHeight = maxHeight * if (state.surface == TravisSurface.AR) .34f else .45f
                    if (state.surface == TravisSurface.MAP) map(Modifier.fillMaxSize())
                    else if (!state.captureMode) TravisCameraGuidance(state, { cameraModifier ->
                        if(liveCameraVisible)camera(cameraModifier)else Box(cameraModifier.background(TravisGreen))
                    }, Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize().background(TravisGreen))

                    Column(Modifier.align(Alignment.TopCenter).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                            Surface(shape = RoundedCornerShape(20.dp), color = palette.surface, shadowElevation = 2.dp) {
                                Row(Modifier.padding(4.dp)) {
                                    listOf(TravisSurface.MAP to "Map", TravisSurface.AR to "Camera").forEach { (surface, title) ->
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
                            shape = RoundedCornerShape(24.dp), color = palette.surface.copy(alpha = if (state.surface == TravisSurface.AR) .94f else 1f),
                            shadowElevation = 8.dp, border = BorderStroke(1.dp, palette.outlineVariant.copy(alpha = .55f)),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().heightIn(max = dockMaxHeight).verticalScroll(rememberScrollState()).padding(if (state.surface == TravisSurface.AR) 12.dp else 16.dp).animateContentSize(tween(180)),
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
                if (active == TravisSheet.ASK) {
                    AskSheet(state, act) { sheet = null }
                } else Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (active) {
                        TravisSheet.ASK -> Unit
                        TravisSheet.MEMORIES -> MemoriesSheet(state, act) { sheet = null }
                        TravisSheet.JOURNEYS -> JourneysSheet(state, act) { sheet = null }
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
            val isRoute = state.mode == GuidanceMode.RETRACE
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.targetPhoto.isNotBlank()) TravisPhoto(state.targetPhoto, "Original photo of the next saved checkpoint", Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Eyebrow(if (isRoute) "BACK TO ${state.destinationTitle.uppercase()}" else "DIRECT BEARING · ${state.destinationTitle.uppercase()}")
                    Text(if (isRoute) "Next: ${state.routeSteps.firstOrNull { it.index == state.routeTargetIndex }?.title ?: state.targetTitle.removePrefix("Next ")}" else state.targetTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (isRoute) "${distanceLabel(state.remainingMeters)} along your trail · waypoint ${state.routeStepNumber.coerceAtLeast(1)} of ${state.routeStepCount.coerceAtLeast(1)}" else "${distanceLabel(state.distanceMeters)} straight line · not a route", style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                }
                IconButton(onClick = { guidanceTools = !guidanceTools }, modifier = Modifier.semantics { contentDescription = if (guidanceTools) "Hide route details" else "Show route details" }) {
                    TravisGlyph("info", Modifier.size(22.dp), palette.primary)
                }
            }
            if (isRoute && state.routeStepCount > 0) LinearProgressIndicator(progress = { state.routeProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = TravisOrange, trackColor = palette.outlineVariant)
            val recognized = state.recognizedMemoryId != null && state.recognizedMemoryId == state.selectedMemoryId
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryAction(if (recognized) "Place recognized" else if (isRoute) "I'm at this waypoint" else "I recognize this place", if (recognized) "check" else "trail", Modifier.weight(1f), state.canConfirm && !recognized) { act(TravisAction.Confirm) }
                QuietAction("Travis", "spark", Modifier.widthIn(min = 90.dp), onClick = onAsk)
            }
            if (!state.canConfirm && !recognized) Text(when {
                state.fixQuality != FixQuality.USABLE -> "A fresh, accurate GPS fix is needed to confirm."
                (state.target?.accuracy ?: 0f) > 25f -> "This saved location is too uncertain to confirm by GPS. Compare the original photo."
                (state.fix?.accuracy ?: 0f) > 15f -> "GPS is ±${state.fix?.accuracy?.toInt()} m. Wait for a more accurate fix before confirming."
                (state.distanceMeters ?: Double.POSITIVE_INFINITY) > 12.0 -> "Keep following the arrow · ${distanceLabel(state.distanceMeters)} to this waypoint."
                else -> "You're close, but GPS uncertainty is still too large to confirm. Wait for a steadier fix."
            }, style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant)
            if (state.canConfirm && !recognized) Text("Close enough to check. Compare your surroundings before confirming.", style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant)
            if (guidanceTools) {
                if (state.guidanceStatus.isNotBlank()) Text(state.guidanceStatus, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                if (isRoute) RouteItinerary(state)
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
            if (memory?.pickup == PickupPhase.COLLECTED) Text("Collected · saved to this journey", style = MaterialTheme.typography.labelMedium, color = palette.primary)
            if (memory != null && memory.pickup !in listOf(PickupPhase.NONE, PickupPhase.COLLECTED)) {
                OutlinedButton(onClick = { act(TravisAction.Collect(memory.id)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("I collected it") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onMemories) { Text("Change destination") }
                if (state.mode == GuidanceMode.FIND_LANDMARK && state.canResumeRetrace) TextButton(onClick = { act(TravisAction.ResumeRetrace) }) { Text("Resume trail") }
                else if (state.replay) TextButton(onClick = { act(TravisAction.ReplayNext) }) { Text("Replay step") }
                else TextButton(onClick = { act(TravisAction.StopGuidance) }) { Text("Stop") }
            }
            if (guidanceTools) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (isRoute) TextButton(onClick = { act(TravisAction.Undo) }) { Text("Undo last waypoint") }
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
            PrimaryAction("Start journey", "trail", Modifier.fillMaxWidth()) { act(TravisAction.Start) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { act(TravisAction.Explore) }) { Text("Explore the demo  →") }
                TextButton(onClick = onPrepare) { Text("Prepare") }
            }
        }
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow(when (outing.phase) {
                        OutingPhase.RECORDING -> if (state.replay) "EXPLORING · SAMPLE OUTING" else "RECORDING YOUR JOURNEY"
                        OutingPhase.PAUSED -> "RECORDING PAUSED"
                        OutingPhase.ENDED -> "JOURNEY SAVED"
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
                PrimaryAction(when (outing.phase) { OutingPhase.PAUSED -> "Resume journey"; OutingPhase.ENDED -> "Review saved places"; else -> "Save a place" }, if (outing.phase == OutingPhase.PAUSED) "play" else "camera", Modifier.weight(1f)) {
                    if (outing.phase == OutingPhase.ENDED) onMemories() else act(if (outing.phase == OutingPhase.PAUSED) TravisAction.Start else TravisAction.Capture)
                }
                QuietAction("Ask", "spark", Modifier.widthIn(min = 86.dp), onClick = onAsk)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { act(TravisAction.HeadBack) }, enabled = outing.points.size > 1) { TravisGlyph("back", Modifier.size(16.dp), palette.primary); Spacer(Modifier.width(6.dp)); Text("Return to start") }
                TextButton(onClick = onMemories) { Text("Your places") }
                if (state.replay) TextButton(onClick = { act(TravisAction.ReplayNext) }) { Text("Next step") }
                else if (outing.phase != OutingPhase.ENDED) TextButton(onClick = { act(TravisAction.End) }) { Text("Finish journey") }
                else TextButton(onClick = { act(TravisAction.Start) }) { Text("Start a new journey") }
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
private fun JourneysSheet(state: TravisUiState, act: (TravisAction) -> Unit, close: () -> Unit) {
    Eyebrow("SAVED ON THIS PHONE")
    Text("Your journeys", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    if (state.outing.phase != OutingPhase.READY && state.outing.phase != OutingPhase.ENDED) {
        Text("${state.outing.name} is ${if (state.outing.phase == OutingPhase.RECORDING) "recording" else "paused"}. Finish this journey before starting another.", style = MaterialTheme.typography.bodyMedium)
        PrimaryAction("Continue current journey", "trail", Modifier.fillMaxWidth()) { if (state.outing.phase == OutingPhase.PAUSED) act(TravisAction.Start); close() }
    } else PrimaryAction("Start a new journey", "trail", Modifier.fillMaxWidth()) { act(TravisAction.Start); close() }
    state.savedOutings.sortedByDescending { it.startedAt }.forEach { outing ->
        Surface(onClick = { act(TravisAction.OpenOuting(outing.id)); close() }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                outing.memories.firstOrNull()?.let { TravisPhoto(it.photoPath, "Original photo from ${outing.name}", Modifier.size(50.dp).clip(RoundedCornerShape(10.dp))) }
                Column(Modifier.weight(1f)) {
                    Text(outing.name, fontWeight = FontWeight.SemiBold)
                    Text("${SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(outing.startedAt))} · ${outing.memories.size} saved places", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (outing.id == state.outing.id) "Open" else "View", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (state.savedOutings.isEmpty()) Text("Your journeys will appear here after you start recording.", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun MemoriesSheet(state: TravisUiState, act: (TravisAction) -> Unit, close: () -> Unit) {
    Eyebrow("YOUR OUTING · ${state.outing.memories.size} PLACES")
    Text("Your saved places.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
                if (ReturnRoute.plan(state.outing, memory.id) != null) {
                    PrimaryAction("Find in AR via my trail", "ar", Modifier.fillMaxWidth()) { act(TravisAction.TakeMeBack(memory.id)); act(TravisAction.SwitchSurface(TravisSurface.AR)); onCloseMemoryCard() }
                    Text("Follow the recorded route to this place.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Direct bearing only") }
                } else {
                    if (memory.point != null) Text("No connected return route available. A direct bearing does not show a walkable path.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onClick, enabled = memory.point != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Direct bearing only") }
                }
                if (memory.pickup == PickupPhase.COLLECTED) Text("Collected · saved to this journey", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
        Text("Saved journeys", style = MaterialTheme.typography.titleMedium)
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
