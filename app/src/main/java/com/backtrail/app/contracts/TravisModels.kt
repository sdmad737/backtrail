package com.backtrail.app.contracts

import kotlinx.serialization.Serializable

@Serializable data class TrailPoint(
    val id: String, val latitude: Double, val longitude: Double,
    val accuracy: Float, val time: Long, val segment: Int,
)
@Serializable enum class OutingPhase { READY, RECORDING, PAUSED, ENDED }
@Serializable enum class PickupPhase { NONE, WAITING_TO_LEAVE, ARMED, NOTIFIED, COLLECTED }
@Serializable data class Memory(
    val id: String, val title: String, val note: String = "", val photoPath: String = "",
    val capturedAt: Long, val point: TrailPoint? = null, val routeIndex: Int? = null,
    val description: String = "", val indexing: String = "Not interpreted",
    val pickup: PickupPhase = PickupPhase.NONE,
)
@Serializable data class Outing(
    val id: String = "", val name: String = "A little further", val startedAt: Long = 0,
    val phase: OutingPhase = OutingPhase.READY, val segment: Int = 0,
    val points: List<TrailPoint> = emptyList(), val memories: List<Memory> = emptyList(),
    val revision: Long = 0, val retraceIndex: Int? = null,
)
enum class GuidanceMode { NONE, RETRACE, FIND_LANDMARK }
enum class TravisSurface { MAP, AR }
data class TravisUiState(
    val outing: Outing = Outing(), val savedOutings: List<Outing> = emptyList(),
    val fix: TrailPoint? = null, val fixQuality: FixQuality = FixQuality.UNAVAILABLE,
    val heading: Double? = null, val headingReliable: Boolean = false,
    val surface: TravisSurface = TravisSurface.MAP, val mode: GuidanceMode = GuidanceMode.NONE,
    val selectedMemoryId: String? = null, val target: TrailPoint? = null,
    val targetTitle: String = "Choose a saved place", val targetPhoto: String = "",
    val bearing: Double? = null, val distanceMeters: Double? = null,
    val remainingMeters: Double? = null, val canConfirm: Boolean = false,
    val replay: Boolean = false, val replayStep: Int = 0,
    val modelReady: Boolean = false, val busy: Boolean = false,
    val answer: String = "", val resultIds: List<String> = emptyList(),
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
    data class SwitchSurface(val surface: TravisSurface) : TravisAction
    data class SelectMemory(val id: String) : TravisAction
    data class Ask(val query: String) : TravisAction
    data class SavePhoto(val note: String, val pickup: Boolean) : TravisAction
    data class Collect(val id: String) : TravisAction
    data class OpenOuting(val id: String) : TravisAction
    data class DemoIssue(val issue: String) : TravisAction
    data class SetReplayHeading(val degrees: Double) : TravisAction
    data class SetTimeReminder(val minutes: Int) : TravisAction
}
