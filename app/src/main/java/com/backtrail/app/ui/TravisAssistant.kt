package com.backtrail.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.backtrail.app.contracts.*

/** A conversation about the current journey; every source card remains an explicit action. */
@Composable
internal fun AskSheet(state: TravisUiState, act: (TravisAction) -> Unit, close: () -> Unit) {
    var query by rememberSaveable(state.outing.id) { mutableStateOf("") }
    var photoMode by rememberSaveable { mutableStateOf(false) }
    var selectedPhotoId by rememberSaveable(state.outing.id) { mutableStateOf(state.outing.memories.lastOrNull()?.id.orEmpty()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val historyScroll = rememberScrollState()
    val palette = MaterialTheme.colorScheme
    val latestAct by rememberUpdatedState(act)
    DisposableEffect(Unit) {
        onDispose { latestAct(TravisAction.CancelVoice); latestAct(TravisAction.StopSpeaking) }
    }
    val canSend = query.isNotBlank() && !state.voiceRecording && !state.voiceProcessing && !state.busy && (state.modelReady || state.replay) && (!photoMode || selectedPhotoId.isNotBlank())
    fun send() {
        if (!canSend) return
        val question = query.trim()
        query = ""
        keyboard?.hide()
        act(if (photoMode) TravisAction.AskAboutPhoto(selectedPhotoId, question) else TravisAction.Chat(question))
    }
    LaunchedEffect(state.assistantMessages.size, state.answer, state.busy) {
        withFrameNanos { }
        historyScroll.animateScrollTo(historyScroll.maxValue, tween(180))
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(palette.primary, CircleShape), contentAlignment = Alignment.Center) { TravisGlyph("spark", Modifier.size(22.dp), palette.onPrimary) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Ask Travis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(if (state.replay) "Sample conversation · Explore" else "Gemma 4 · on this phone", style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant)
            }
            TextButton(onClick = close) { Text("Done") }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(historyScroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.notice.isNotBlank()) Surface(color = palette.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.notice, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { act(TravisAction.DismissNotice) }) { Text("OK") }
                }
            }
            Surface(color = palette.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
                Text(if (state.mode == GuidanceMode.RETRACE) "Following your trail to ${state.destinationTitle}. Next: ${state.routeSteps.firstOrNull { it.index == state.routeTargetIndex }?.title ?: state.targetTitle.removePrefix("Next ")}." else "${state.outing.memories.size} saved places · your recorded journey and notes", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
            }
            if (state.assistantMessages.isEmpty() && !photoMode) {
                Text("What do you want to remember?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Ask about places, the order you passed them, or what you left behind. I'll use this journey's saved details.", style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant)
                listOf("What did I pass on this journey?", "What did I leave to collect?", "Which place came before this one?").forEach { suggestion ->
                    OutlinedButton(onClick = { query = suggestion }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(suggestion) }
                }
            }
            state.assistantMessages.forEach { message ->
                val user = message.role == AssistantRole.USER
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                    Surface(color = if (user) palette.primary else palette.surfaceContainer, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (user) 18.dp else 5.dp, bottomEnd = if (user) 5.dp else 18.dp), modifier = Modifier.widthIn(max = 340.dp).animateContentSize(tween(160))) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (!user) Text(when (message.status) {
                                AssistantAnswerStatus.SAMPLE -> "SAMPLE RESPONSE"
                                AssistantAnswerStatus.UNAVAILABLE -> "MODEL UNAVAILABLE"
                                AssistantAnswerStatus.FAILURE -> "COULDN'T ANSWER"
                                AssistantAnswerStatus.LIVE -> "TRAVIS · LOCAL GEMMA"
                                else -> "TRAVIS"
                            }, style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant)
                            Text(message.text, color = if (user) palette.onPrimary else palette.onSurface, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (!user && message.status == AssistantAnswerStatus.LIVE) TextButton(onClick = { act(if (state.speaking) TravisAction.StopSpeaking else TravisAction.ReadAloud(message.text)) }) { Text(if (state.speaking) "Stop speaking" else "Read aloud") }
                    message.sourceIds.mapNotNull { id -> state.outing.memories.firstOrNull { it.id == id } }.distinctBy { it.id }.forEach { memory ->
                        AssistantSource(memory, act, close)
                    }
                }
            }
            if (photoMode) {
                Text("Ask about an original photo", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.outing.memories.takeLast(8).forEach { memory ->
                        Surface(onClick = { selectedPhotoId = memory.id }, color = if (memory.id == selectedPhotoId) palette.secondaryContainer else palette.surfaceContainer, shape = RoundedCornerShape(13.dp), enabled = !state.busy) {
                            Column(Modifier.width(92.dp).padding(6.dp)) {
                                TravisPhoto(memory.photoPath, "Original saved photo of ${memory.title}", Modifier.size(80.dp).clip(RoundedCornerShape(9.dp)))
                                Text(memory.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (state.assistantMode == AssistantMode.PHOTO_QUESTION && state.answer.isNotBlank()) Surface(color = palette.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow(if (state.answerStatus == AssistantAnswerStatus.SAMPLE) "SAMPLE PHOTO RESPONSE" else if (state.answerStatus == AssistantAnswerStatus.LIVE) "GEMMA · PHOTO INTERPRETATION" else "PHOTO RESPONSE STATUS")
                        Text(state.answer)
                        Text("Use the original photo to check this interpretation.", style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant)
                    }
                }
            }
            if (!state.modelReady && !state.replay) {
                Text("Import the local model to talk with Travis. Your trail and photos are still available.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { act(TravisAction.ImportModel) }) { Text("Import Gemma") }
            }
            if (state.busy) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(state.inferenceMessage.ifBlank { "Thinking about your journey…" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { act(TravisAction.CancelInference) }) { Text("Stop") }
            }
        }
        HorizontalDivider(color = palette.outlineVariant)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                FilterChip(selected = photoMode, onClick = { photoMode = !photoMode }, enabled = !state.busy, label = { Text(if (photoMode) "Photo selected" else "Ask about a photo") }, leadingIcon = { TravisGlyph("camera", Modifier.size(16.dp), palette.primary) })
                if (state.assistantMessages.isNotEmpty()) TextButton(onClick = { act(TravisAction.ClearConversation) }, enabled = !state.busy) { Text("Clear chat") }
            }
            if (state.voiceStatus.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (state.voiceRecording) Box(Modifier.size(8.dp).background(TravisOrange, CircleShape))
                if (state.voiceRecording) Spacer(Modifier.width(7.dp))
                Text(state.voiceStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                if (state.voiceRecording || state.voiceProcessing) TextButton(onClick = { act(TravisAction.CancelVoice) }) { Text("Cancel") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { act(if (state.voiceRecording) TravisAction.StopVoice else TravisAction.StartVoice) }, enabled = !state.voiceProcessing && (!state.busy || state.voiceRecording) && state.modelReady && !state.replay, modifier = Modifier.heightIn(min = 54.dp), contentPadding = PaddingValues(horizontal = 12.dp), shape = RoundedCornerShape(20.dp)) {
                    Text(if (state.voiceRecording) "Finish" else "Voice", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedTextField(value = query, onValueChange = { query = it.take(500) }, modifier = Modifier.weight(1f), placeholder = { Text(if (photoMode) "Ask about this photo…" else "Ask about your journey…") }, shape = RoundedCornerShape(24.dp), minLines = 1, maxLines = 3, enabled = !state.busy && !state.voiceRecording && !state.voiceProcessing, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
                FilledIconButton(onClick = { send() }, enabled = canSend, modifier = Modifier.size(54.dp).semantics { contentDescription = "Send to Travis" }) { TravisGlyph("north", Modifier.size(23.dp), if (canSend) palette.onPrimary else palette.onSurface.copy(alpha = .38f)) }
            }
        }
    }
}

@Composable
private fun AssistantSource(memory: Memory, act: (TravisAction) -> Unit, close: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Surface(color = palette.surface, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, palette.outlineVariant), modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TravisPhoto(memory.photoPath, "Original saved photo of ${memory.title}", Modifier.size(58.dp).clip(RoundedCornerShape(9.dp)))
            Column(Modifier.weight(1f)) {
                Text(memory.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (memory.routeIndex != null) "Saved on your recorded trail" else "Saved photo · route position unknown", style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant)
                TextButton(onClick = {
                    act(if (memory.routeIndex != null) TravisAction.TakeMeBack(memory.id) else TravisAction.SelectMemory(memory.id))
                    act(TravisAction.SwitchSurface(TravisSurface.AR)); close()
                }, enabled = memory.point != null, contentPadding = PaddingValues(vertical = 4.dp)) { Text(if (memory.routeIndex != null) "Find in AR via trail" else "View direct bearing") }
            }
        }
    }
}

@Composable
internal fun RouteItinerary(state: TravisUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Your way back", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        state.routeSteps.take(12).forEachIndexed { step, point ->
            val current = point.index == state.routeTargetIndex
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(26.dp).background(if (current) TravisOrange else MaterialTheme.colorScheme.surfaceContainer, CircleShape), contentAlignment = Alignment.Center) { Text("${step + 1}", style = MaterialTheme.typography.labelSmall, color = if (current) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(point.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                if (current) Text("NEXT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (state.routeSteps.size > 12) Text("${state.routeSteps.size - 12} more waypoints on the recorded route", style = MaterialTheme.typography.labelSmall)
    }
}
