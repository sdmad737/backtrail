package com.backtrail.app.contracts

import com.backtrail.app.ai.RightWayCheck
import kotlinx.serialization.Serializable

@Serializable data class TrailPoint(
    val id: String, val latitude: Double, val longitude: Double,
    val accuracy: Float, val time: Long, val segment: Int,
)
@Serializable enum class OutingPhase { READY, RECORDING, PAUSED, ENDED }
@Serializable enum class PickupPhase { NONE, WAITING_TO_LEAVE, ARMED, NOTIFIED, COLLECTED }
@Serializable enum class ArrivalPhase { NONE, ARMED, NOTIFIED }
@Serializable data class Memory(
    val id: String, val title: String, val note: String = "", val photoPath: String = "",
    val capturedAt: Long, val point: TrailPoint? = null, val routeIndex: Int? = null,
    val description: String = "", val indexing: String = "Not interpreted",
    val pickup: PickupPhase = PickupPhase.NONE,
    val arrival: ArrivalPhase = ArrivalPhase.NONE, val pickupTask: String = "",
)
@Serializable data class SavedGuidance(
    val mode: GuidanceMode = GuidanceMode.NONE, val memoryId: String? = null,
    val returnIndex: Int? = null, val goalIndex: Int? = null, val startIndex: Int? = null,
)
@Serializable data class Outing(
    val id: String = "", val name: String = "A little further", val startedAt: Long = 0,
    val phase: OutingPhase = OutingPhase.READY, val segment: Int = 0,
    val points: List<TrailPoint> = emptyList(), val memories: List<Memory> = emptyList(),
    val revision: Long = 0, val retraceIndex: Int? = null,
    val endedAt: Long? = null, val guidance: SavedGuidance = SavedGuidance(),
    val suspendedGuidance: SavedGuidance? = null,
    val mapRegion: String = "bundled-usgs-san-mateo",
)
@Serializable enum class GuidanceMode { NONE, RETRACE, FIND_LANDMARK }
enum class TravisSurface { MAP, AR }
enum class AssistantMode { FIND_PLACE, TRIP_QUESTION, PHOTO_QUESTION }
enum class AssistantAnswerStatus { NONE, LIVE, UNAVAILABLE, FAILURE, INFO, SAMPLE }
@Serializable data class ReminderSuggestion(val memoryId: String, val task: String)
data class TravisUiState(
    val outing: Outing = Outing(), val savedOutings: List<Outing> = emptyList(),
    val fix: TrailPoint? = null, val fixQuality: FixQuality = FixQuality.UNAVAILABLE,
    val heading: Double? = null, val headingReliable: Boolean = false,
    val mapHeading: Double? = null, val mapHeadingReliable: Boolean = false,
    val surface: TravisSurface = TravisSurface.MAP, val mode: GuidanceMode = GuidanceMode.NONE,
    val selectedMemoryId: String? = null, val target: TrailPoint? = null,
    val targetTitle: String = "Choose a saved place", val targetPhoto: String = "",
    val destinationMemoryId: String? = null, val destinationTitle: String = "Recorded start",
    val destinationPhoto: String = "", val destinationDistanceMeters: Double? = null,
    val routeTargetIndex: Int? = null, val returnRouteStartIndex: Int? = null,
    val returnRouteEndIndex: Int? = null, val canReturnAlongRoute: Boolean = false,
    val guidanceStatus: String = "",
    val canResumeRetrace: Boolean = false,
    val notificationMemory: Memory? = null, val notificationOutingName: String = "",
    val bearing: Double? = null, val distanceMeters: Double? = null,
    val remainingMeters: Double? = null, val canConfirm: Boolean = false,
    val replay: Boolean = false, val replayStep: Int = 0,
    val modelReady: Boolean = false, val busy: Boolean = false,
    val modelBackend: String = "Not loaded", val modelFileName: String = "",
    val modelLoadMillis: Long? = null, val modelGenerateMillis: Long? = null,
    val modelOutcome: String = "Not exercised in this session",
    val answer: String = "", val resultIds: List<String> = emptyList(),
    val assistantMode: AssistantMode = AssistantMode.FIND_PLACE,
    val answerStatus: AssistantAnswerStatus = AssistantAnswerStatus.NONE,
    val answerGrounded: Boolean? = null, val answerConfidence: String? = null,
    val safetyRefusal: Boolean = false, val pendingReminder: ReminderSuggestion? = null,
    val inferenceMessage: String = "", val retryDescriptionId: String? = null,
    val describingMemoryId: String? = null,
    val reminderMessageMemoryId: String? = null,
    val rightWayResult: RightWayCheck.Result? = null,
    val comparisonVerdict: String? = null, val comparisonReasons: List<String> = emptyList(),
    val notice: String = "", val sensorStatus: String = "", val mapAvailable: Boolean = false,
    val cameraAllowed: Boolean = false, val locationAllowed: Boolean = false,
    val notificationsAllowed: Boolean = false, val captureMode: Boolean = false,
    val captureBusy: Boolean = false,
)
sealed interface TravisAction {
    data object Start : TravisAction
    data object Pause : TravisAction
    data object End : TravisAction
    data object HeadBack : TravisAction
    data object Confirm : TravisAction
    data object Undo : TravisAction
    data object ResumeRetrace : TravisAction
    data object StopGuidance : TravisAction
    data object Explore : TravisAction
    data object ExitExplore : TravisAction
    data object ReplayNext : TravisAction
    data object Capture : TravisAction
    data object CancelCapture : TravisAction
    data object ImportModel : TravisAction
    data object CancelInference : TravisAction
    data object RequestPermissions : TravisAction
    data object DismissNotice : TravisAction
    data object DismissNotification : TravisAction
    data class SwitchSurface(val surface: TravisSurface) : TravisAction
    data class SelectMemory(val id: String) : TravisAction
    data class TakeMeBack(val id: String) : TravisAction
    data class UpdateMemoryNote(val id: String, val note: String) : TravisAction
    data class DeleteMemory(val id: String) : TravisAction
    data class SetArrivalReminder(val id: String, val enabled: Boolean) : TravisAction
    data object DismissAssistantResult : TravisAction
    data object CheckDirection : TravisAction
    data class Ask(val query: String) : TravisAction
    data class AskAboutTrip(val query: String) : TravisAction
    data class AskAboutPhoto(val memoryId: String, val query: String) : TravisAction
    data class SuggestReminder(val memoryId: String) : TravisAction
    data object ConfirmReminder : TravisAction
    data object DismissReminder : TravisAction
    data class RetryDescription(val memoryId: String) : TravisAction
    data class UpdateMemory(val id: String, val title: String, val description: String, val note: String? = null) : TravisAction
    data class CompareCurrentPhoto(val memoryId: String) : TravisAction
    data class SavePhoto(val note: String, val pickup: Boolean) : TravisAction
    data class Collect(val id: String) : TravisAction
    data class OpenOuting(val id: String) : TravisAction
    data class DemoIssue(val issue: String) : TravisAction
    data class SetReplayHeading(val degrees: Double) : TravisAction
    data class SetTimeReminder(val minutes: Int) : TravisAction
}
