package com.backtrail.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.backtrail.app.ai.*
import com.backtrail.app.contracts.*
import com.backtrail.app.device.*
import com.backtrail.app.trip.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TravisViewModel(app:Application):AndroidViewModel(app) {
    private val store=OutingStore.get(app)
    private val models=ModelStore(app)
    private var ownedInference: LocalVisionService? = null
    private val modelChangeMutex = Mutex()
    private suspend fun inference(): LocalVisionService {
        if(modelChangeMutex.isLocked) mutable.update { it.copy(busy=true,inferenceMessage="Finishing local model setup…") }
        return modelChangeMutex.withLock {
            ownedInference ?: run {
                val (file, backend) = models.preferred ?: (models.file to InferenceBackend.GPU)
                LocalVisionService(file, getApplication<Application>().cacheDir, backend,
                    nativeLibraryDir=getApplication<Application>().applicationInfo.nativeLibraryDir,
                    traceSink={ trace -> mutable.update { it.copy(modelBackend=backend.name,modelFileName=file.name,
                        modelLoadMillis=trace.initMs,modelGenerateMillis=trace.generateMs,modelOutcome=trace.outcome) } }
                ).also { ownedInference=it }
            }
        }
    }
    private val mutable=MutableStateFlow(TravisUiState())
    val state=mutable.asStateFlow()
    val camera=CameraCaptureController()
    private val preferences=app.getSharedPreferences("travis",0)
    private var currentId:String?=preferences.getString("active",null)
    private var liveOuting=Outing()
    private var replayOuting:Outing?=null
    private var replayFix:TrailPoint?=null
    private var replayHeading=20.0
    private var issue="normal"
    private var inferenceJob:Job?=null
    private var session=UUID.randomUUID().toString()
    private val gate=RequestGate()
    private val undo=mutableListOf<Int>()
    private var undoContext:ReturnRoute.UndoContext?=null
    private val voice=LocalVoice(app)
    private var voiceJob:Job?=null
    private var confirmationPending=false
    private var journeyTransition=false
    private var photoDraft:Memory?=null
    private var photoDraftOutingId:String?=null
    private var photoSaveInFlight=false
    private var captureOrigin=TravisSurface.MAP
    private val routeFollower=RouteFollower()
    private var navigationAdvancePending=false
    private var speech:LocalSpeech?=null
    private val recentFixes=mutableListOf<TrailPoint>()
    init {
        viewModelScope.launch { store.outings.collect { items -> liveOuting=items.find { it.id==currentId } ?: Outing();refresh() } }
        viewModelScope.launch { combine(LiveSensors.fix,LiveSensors.failure) { _,_->Unit }.collect { refresh() } }
        viewModelScope.launch { combine(LiveSensors.heading,LiveSensors.headingReliable) { heading,reliable->heading to reliable }.collect { (heading,reliable)->
            if(!mutable.value.replay)mutable.update { it.copy(heading=heading,headingReliable=reliable,headingAccuracyDegrees=LiveSensors.headingAccuracyDegrees.value,headingStatus=LiveSensors.headingStatus.value) }
        } }
        viewModelScope.launch { combine(LiveSensors.mapHeading,LiveSensors.mapHeadingReliable) { heading,reliable->heading to reliable }.collect { (heading,reliable)->
            if(!mutable.value.replay)mutable.update { it.copy(mapHeading=heading,mapHeadingReliable=reliable) }
        } }
        viewModelScope.launch { store.loadError.filterNotNull().collect { announce(it) } }
        viewModelScope.launch { while(isActive) {
            delay(1000)
            val state=mutable.value
            if(mutable.subscriptionCount.value>0 && !state.replay && (state.lastKnownGuidance || TrailMath.quality(state.fix,System.currentTimeMillis())!=state.fixQuality || (state.directionUsable && state.fix?.let { System.currentTimeMillis()-it.time>5_000 }==true)))refresh()
        } }
        refreshPermissions()
    }
    fun ensureGuidanceTracking() {
        val state=mutable.value
        if(!state.replay && state.locationAllowed && state.mode!=GuidanceMode.NONE &&
            state.outing.id.isNotBlank() && state.outing.id==currentId && !LiveSensors.trackingRunning.value)
            startGuidanceLocation(state.outing.id)
    }
    fun refreshPermissions() {
        val app=getApplication<Application>()
        mutable.update { it.copy(cameraAllowed=app.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED,
            locationAllowed=app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED,
            notificationsAllowed=TravisNotifications.allowed(app),modelReady=models.ready) }
    }
    private fun refresh() {
        val old=mutable.value
        val stored=replayOuting ?: liveOuting
        val outing=if(!old.replay && stored.phase==OutingPhase.RECORDING && !LiveSensors.trackingRunning.value) stored.copy(phase=OutingPhase.PAUSED) else stored
        val guide=outing.guidance
        val mode=guide.mode
        if(!old.replay) LiveSensors.guidanceMode.value=mode
        val fix=if(old.replay) replayFix else LiveSensors.fix.value
        if(!old.replay && fix!=null && recentFixes.lastOrNull()?.time!=fix.time) recentFixes.add(fix)
        recentFixes.removeAll { it.time < System.currentTimeMillis()-30_000L }
        val quality=if(old.replay) when(issue) {"weak_gps"->FixQuality.APPROXIMATE;"stale_gps"->FixQuality.STALE;else->if(fix==null) FixQuality.UNAVAILABLE else FixQuality.USABLE} else TrailMath.quality(fix,System.currentTimeMillis())
        val destination=outing.memories.find { it.id==guide.memoryId }
        val index=if(mode==GuidanceMode.RETRACE) ReturnRoute.targetIndex(outing) else null
        val target=when(mode) { GuidanceMode.RETRACE->index?.let { outing.points.getOrNull(it) };GuidanceMode.FIND_LANDMARK->destination?.point;else->null }
        val nextPhoto=if(mode==GuidanceMode.RETRACE) outing.memories.firstOrNull { it.routeIndex==index } else destination
        val goal=if(guide.memoryId!=null)guide.goalIndex else 0
        val targetChanged=old.outing.id!=outing.id || old.mode!=mode || old.routeTargetIndex!=index || old.selectedMemoryId!=guide.memoryId
        val usable=quality==FixQuality.USABLE
        val itinerary=ReturnRoute.itinerary(outing)
        val navigation=if(mode==GuidanceMode.RETRACE && !old.replay)routeFollower.update(outing,fix,System.currentTimeMillis())else null
        if(mode!=GuidanceMode.RETRACE)routeFollower.reset()
        val directDistance=if(usable && fix!=null && target!=null)TrailMath.distance(fix,target)else null
        val directUncertain=directDistance!=null && fix!=null && target!=null && directDistance<=fix.accuracy+target.accuracy
        val navMessage=when(navigation?.status) {
            RouteFollower.Status.ACQUIRING->"Matching your position to this section of trail…"
            RouteFollower.Status.FOLLOWING->"Following your recorded trail · progress updates as you walk"
            RouteFollower.Status.WEAK_GPS->"GPS needs a fresh fix within ±15 m. Move into open sky."
            RouteFollower.Status.POSITION_UNCERTAIN->"This distance is smaller than GPS uncertainty. Follow the map and original photo."
            RouteFollower.Status.NEAR_DESTINATION->"GPS uncertainty overlaps this place. Use the original photo to recognize it."
            RouteFollower.Status.OFF_ROUTE->"Your position is away from this recorded section. Check the map before continuing."
            RouteFollower.Status.AMBIGUOUS->"Several nearby trail sections could match. The arrow points to the saved waypoint; check the map."
            RouteFollower.Status.GAP->"The recorded route ends at a gap. No connecting path is inferred."
            else->if(mode==GuidanceMode.FIND_LANDMARK && fix?.let { System.currentTimeMillis()-it.time>5_000 }==true)"Waiting for a fresh GPS update."else if(mode==GuidanceMode.FIND_LANDMARK && directUncertain)"GPS cannot resolve a direction this close. Compare the original photo."else ""
        }
        val remaining=if(mode==GuidanceMode.RETRACE && usable)navigation?.remainingMeters?:ReturnRoute.remaining(outing,fix)else null
        val directionUsable=when {
            old.replay->usable && target!=null
            mode==GuidanceMode.RETRACE->navigation?.bearing!=null
            mode==GuidanceMode.FIND_LANDMARK->usable && !directUncertain && fix!=null && System.currentTimeMillis()-fix.time in 0..5_000 && fix.accuracy<=15 && target!=null
            else->false
        }
        val measured=old.copy(outing=outing,assistantMessages=if(old.outing.id==outing.id)old.assistantMessages else emptyList(),recognizedMemoryId=old.recognizedMemoryId?.takeIf { it==guide.memoryId },savedOutings=store.outings.value,fix=fix,fixQuality=quality,
            mode=mode,selectedMemoryId=guide.memoryId,
            heading=if(old.replay) replayHeading else LiveSensors.heading.value,
            headingReliable=if(old.replay)true else LiveSensors.headingReliable.value,
            headingAccuracyDegrees=if(old.replay)3.0 else LiveSensors.headingAccuracyDegrees.value,
            headingStatus=if(old.replay)"Sample heading" else LiveSensors.headingStatus.value,
            mapHeading=if(old.replay)replayHeading else LiveSensors.mapHeading.value,
            mapHeadingReliable=if(old.replay)true else LiveSensors.mapHeadingReliable.value,
            target=target,targetTitle=if(mode==GuidanceMode.RETRACE) nextPhoto?.title?:"Next recorded waypoint" else destination?.title?:"Choose a saved place",
            targetPhoto=nextPhoto?.photoPath?:"",routeTargetIndex=index,
            destinationMemoryId=destination?.id,destinationTitle=destination?.title?:"Recorded start",
            destinationPhoto=destination?.photoPath?:outing.memories.firstOrNull { it.routeIndex==0 }?.photoPath?:"",
            destinationDistanceMeters=if(usable && fix!=null && destination?.point!=null)TrailMath.distance(fix,destination.point)else null,
            returnRouteStartIndex=if(mode==GuidanceMode.RETRACE)goal else null,
            returnRouteEndIndex=if(mode==GuidanceMode.RETRACE)guide.startIndex?:outing.points.lastIndex else null,
            canReturnAlongRoute=destination?.let { ReturnRoute.plan(outing,it.id)!=null }?:false,
            canResumeRetrace=ReturnRoute.canResume(outing),
            routeSteps=itinerary?.steps?.mapIndexed { ordinal,step ->
                val memory=outing.memories.firstOrNull { it.routeIndex==step.index }
                RouteStep(step.index,memory?.title?:if(step.index==goal) "Recorded start" else "Waypoint ${ordinal+1}",memory!=null,step.distanceFromPreviousMeters)
            }?:emptyList(),
            routeStepNumber=itinerary?.let { it.currentStepIndex+1 }?:0,
            routeStepCount=itinerary?.steps?.size?:0,routeProgress=navigation?.progress?:if(!targetChanged && mode==GuidanceMode.RETRACE)old.routeProgress else itinerary?.progress?:0f,
            directionUsable=directionUsable,navigationMessage=navMessage,
            comparisonVerdict=if(targetChanged)null else old.comparisonVerdict,
            comparisonReasons=if(targetChanged)emptyList() else old.comparisonReasons,
            rightWayResult=if(targetChanged)null else old.rightWayResult,
            guidanceStatus=when { mode==GuidanceMode.FIND_LANDMARK->"Direct bearing · a connecting path is not inferred";mode==GuidanceMode.RETRACE && remaining==null && usable->"Recorded segment only · a gap blocks the earlier route";mode==GuidanceMode.RETRACE->"Follow the recorded trail. Intermediate points update automatically; confirm your destination yourself.";else->"" },
            bearing=if(!old.replay && mode==GuidanceMode.RETRACE)navigation?.bearing else if(directionUsable && fix!=null && target!=null)TrailMath.bearing(fix,target)else null,
            distanceMeters=directDistance,
            remainingMeters=remaining,
            canConfirm=if(old.replay)usable && fix!=null && target!=null && TrailMath.distance(fix,target)<=12 else TrailMath.canConfirm(fix,target,System.currentTimeMillis()),
            sensorStatus=if(old.replay)"" else LiveSensors.failure.value,
            mapAvailable=issue!="missing_maps" && fix?.let { it.latitude in 37.549..37.585 && it.longitude in -122.390..-122.345 }==true)
        mutable.value=GuidanceContinuity.present(old,measured,navigation?.status,System.currentTimeMillis(),navigation?.target)
        if(!old.replay) LiveSensors.headingReferenceFix.value=HeadingReference.choose(fix,mutable.value.lastKnownFix ?: outing.points.lastOrNull())
        if(navigation?.passedTargetIndex!=null && navigation.nextTargetIndex!=null)
            advanceMeasuredNavigation(outing,navigation.passedTargetIndex,navigation.nextTargetIndex)

    }
    fun announce(text:String) { mutable.update { it.copy(notice=text) } }
    private fun change(onSaved:(()->Unit)?=null, block:(Outing)->Outing) {
        if(mutable.value.replay) { replayOuting=block(replayOuting?:ExploreFixture.outing());refresh();onSaved?.invoke();return }
        val id=currentId ?: return
        viewModelScope.launch { try { store.mutate(id) { block(it ?: liveOuting) };onSaved?.invoke() } catch(_:Exception) { announce("Could not save this change. Original records remain available.") } }
    }
    private fun invalidate() { voice.stop();voiceJob?.cancel();voiceJob=null;gate.invalidate();inferenceJob?.cancel();mutable.update { it.copy(busy=false,voiceRecording=false,voiceProcessing=false,voiceStatus="",describingMemoryId=null,reminderMessageMemoryId=null,rightWayResult=null,comparisonVerdict=null,comparisonReasons=emptyList(),pendingReminder=null) };session=UUID.randomUUID().toString() }
    fun action(action:TravisAction) {
        when(action) {
            TravisAction.Start -> beginJourney(false)
            is TravisAction.StartNewJourney -> beginJourney(true,action.name)
            is TravisAction.RenameOuting -> renameOuting(action.id,action.name)
            is TravisAction.DeleteOuting -> deleteOuting(action.id)
            TravisAction.Pause -> change { it.copy(phase=OutingPhase.PAUSED,segment=it.segment+1) }
            TravisAction.End -> { invalidate();change { it.copy(phase=OutingPhase.ENDED,endedAt=System.currentTimeMillis(),guidance=SavedGuidance()) };if(!mutable.value.replay) RecordingService.stop(getApplication());announce("Outing saved on this phone.") }
            TravisAction.HeadBack -> {
                invalidate();val outing=mutable.value.outing
                if(outing.points.size<2) { announce("Record at least two measured points before retracing.");return }
                if(!mutable.value.replay && !mutable.value.locationAllowed) { announce("Allow precise location to begin retracing.");return }
                val returning=ReturnRoute.headBack(outing)
                if(returning==null) { announce("A recording gap separates these points. No continuous retrace is available.");return }
                undo.clear();undoContext=null;change { old->ReturnRoute.headBack(old)?.copy(phase=OutingPhase.ENDED,endedAt=old.endedAt?:System.currentTimeMillis())?:old }
                mutable.update { it.copy(captureMode=false,notice=if(returning.guidance.mode==GuidanceMode.NONE)"Back at your recorded start. Outing complete."else"Following the recorded route in reverse order.") }
                if(returning.guidance.mode==GuidanceMode.NONE) { if(!mutable.value.replay)RecordingService.stop(getApplication()) }
                else startGuidanceLocation(outing.id)
            }
            is TravisAction.TakeMeBack -> takeMeBack(action.id)
            TravisAction.Confirm -> confirmTarget()
            TravisAction.Undo -> {
                val context=undoContext
                if(context!=null && context==ReturnRoute.undoContext(mutable.value.outing) && undo.isNotEmpty()) {
                    invalidate();val index=undo.removeAt(undo.lastIndex)
                    change { ReturnRoute.undo(it,context,index)?:it }
                }
            }
            TravisAction.ResumeRetrace -> {
                val resumed=ReturnRoute.resume(mutable.value.outing)
                if(resumed==null) { announce("The recorded segment ends here. No return path is inferred across a gap.");return }
                invalidate();undo.clear();undoContext=null;change { ReturnRoute.resume(it)?:it }
                if(resumed.guidance.mode==GuidanceMode.NONE) {
                    if(!mutable.value.replay)RecordingService.stop(getApplication())
                    announce("Back at your recorded start. Outing complete.")
                } else { mutable.update { it.copy(notice="") };startGuidanceLocation(mutable.value.outing.id) }
            }
            TravisAction.CheckDirection -> {
                val state=mutable.value;val index=state.routeTargetIndex
                if(state.mode!=GuidanceMode.RETRACE || index==null) { announce("Start retracing a recorded outing before checking direction.");return }
                val expected=state.outing.memories.firstOrNull { it.routeIndex==index }
                val verdict=state.comparisonVerdict?.let { runCatching { Verdict.valueOf(it) }.getOrNull() }
                val fixes=if(state.replay) listOfNotNull(replayFix) else recentFixes.toList()
                val result=RightWayCheck.check(RightWayCheck.Input(
                    trail=state.outing.points,targetIndex=index,targetName=expected?.title,recentFixes=fixes,
                    fixQuality=state.fixQuality,compassHeading=state.heading,compassReliable=state.headingReliable,
                    visual=verdict,visualSpotName=expected?.title,
                ))
                mutable.update { it.copy(rightWayResult=result) }
            }
            TravisAction.DismissAssistantResult -> mutable.update { it.copy(answer="",answerStatus=AssistantAnswerStatus.NONE,resultIds=emptyList(),answerGrounded=null,answerConfidence=null,safetyRefusal=false) }
            TravisAction.StopGuidance -> { invalidate();change { it.copy(guidance=SavedGuidance(),suspendedGuidance=ReturnRoute.suspendCurrent(it)) };mutable.update { it.copy(mode=GuidanceMode.NONE,selectedMemoryId=null,surface=TravisSurface.MAP) };if(!mutable.value.replay && mutable.value.outing.phase==OutingPhase.ENDED)RecordingService.stop(getApplication());refresh() }
            TravisAction.Explore -> {
                invalidate();issue="normal";replayOuting=ExploreFixture.outing();replayFix=ExploreFixture.points.last().copy(time=System.currentTimeMillis());replayHeading=20.0
                mutable.update { it.copy(replay=true,mode=GuidanceMode.NONE,surface=TravisSurface.MAP,replayStep=14,selectedMemoryId=null,answer="",resultIds=emptyList(),notice="Explore demo · movement, camera preview and model answers are samples.") };refresh()
            }
            TravisAction.ExitExplore -> { invalidate();replayOuting=null;replayFix=null;issue="normal";mutable.update { it.copy(replay=false,mode=GuidanceMode.NONE,surface=TravisSurface.MAP,selectedMemoryId=null,answer="",resultIds=emptyList(),notice="Live mode · waiting for measured data") };refresh() }
            TravisAction.ReplayNext -> if(mutable.value.replay) {
                val state=mutable.value
                val point=state.target ?: ExploreFixture.points[(state.replayStep+1)%ExploreFixture.points.size]
                replayFix=point.copy(time=System.currentTimeMillis())
                mutable.update { it.copy(replayStep=ExploreFixture.points.indexOfFirst { p->p.id==point.id }.coerceAtLeast(0)) }
                val memory=state.outing.memories.find { it.pickup==PickupPhase.ARMED && it.point?.let { p->TrailMath.distance(p,point)<30 }==true }
                if(memory!=null) { change { it.copy(memories=it.memories.map { m->if(m.id==memory.id)m.copy(pickup=PickupPhase.NOTIFIED)else m }) };announce("Sample arrival reminder · ${memory.note}") }
                refresh()
            }
            TravisAction.Capture -> {
                if(mutable.value.outing.id.isBlank()) { announce("Start a journey before saving a place.");return }
                if(mutable.value.captureMode)return
                invalidate();captureOrigin=mutable.value.surface
                mutable.update { it.copy(surface=TravisSurface.AR,captureMode=true,capturedPhotoPath="",notice="") }
            }
            TravisAction.TakePhoto -> takePhotoDraft()
            TravisAction.RetakePhoto -> discardPhotoDraft(false)
            TravisAction.CancelCapture -> discardPhotoDraft(true)
            is TravisAction.SavePhoto -> savePhoto(action.note,action.pickup)
            is TravisAction.SwitchSurface -> {
                mutable.update { it.copy(surface=action.surface,captureMode=false) }
            }
            is TravisAction.SelectMemory -> {
                invalidate();val memory=mutable.value.outing.memories.find { it.id==action.id }?:return
                change { ReturnRoute.suspendForFind(it,memory.id) }
                mutable.update { it.copy(surface=TravisSurface.AR,captureMode=false,notice="",recognizedMemoryId=null) }
                if(memory.point==null)announce("This photo has no recorded location. Direction is unavailable.")else startGuidanceLocation(mutable.value.outing.id)
            }
            is TravisAction.UpdateMemoryNote -> updateMemoryNote(action.id,action.note)
            is TravisAction.DeleteMemory -> deleteMemory(action.id)
            is TravisAction.SetArrivalReminder -> {
                val memory=mutable.value.outing.memories.find { it.id==action.id }?:return
                if(action.enabled && memory.point==null) { announce("This place has no saved location for an arrival reminder.");return }
                change { it.copy(memories=it.memories.map { m->if(m.id==action.id)m.copy(arrival=if(action.enabled)ArrivalPhase.ARMED else ArrivalPhase.NONE)else m }) }
                announce(if(action.enabled)"Arrival reminder requested. It runs while this outing's location service is active."else"Arrival reminder removed.")
            }
            TravisAction.StartVoice -> startVoice()
            TravisAction.StopVoice -> voice.stop()
            TravisAction.CancelVoice -> cancelVoice()
            is TravisAction.ReadAloud -> {
                if(speech==null)speech=LocalSpeech(getApplication()) { speaking,message -> mutable.update { it.copy(speaking=speaking,voiceStatus=message) } }
                speech?.speak(action.text)
            }
            TravisAction.StopSpeaking -> speech?.stop()
            is TravisAction.Chat -> chat(action.query)
            TravisAction.ClearConversation -> { invalidate();mutable.update { it.copy(assistantMessages=emptyList(),answer="",resultIds=emptyList(),answerStatus=AssistantAnswerStatus.NONE) } }
            is TravisAction.Ask -> ask(action.query)
            is TravisAction.AskAboutTrip -> askAboutTrip(action.query)
            is TravisAction.AskAboutPhoto -> askAboutPhoto(action.memoryId, action.query)
            is TravisAction.SuggestReminder -> suggestReminder(action.memoryId)
            TravisAction.ConfirmReminder -> confirmReminder()
            TravisAction.DismissReminder -> mutable.update { it.copy(pendingReminder=null,reminderMessageMemoryId=null,inferenceMessage="") }
            is TravisAction.RetryDescription -> mutable.value.outing.memories.firstOrNull { it.id==action.memoryId }?.let { describe(it,mutable.value.outing.id) }
            is TravisAction.UpdateMemory -> updateMemory(action.id,action.title,action.description,action.note)
            is TravisAction.CompareCurrentPhoto -> compareCurrentPhoto(action.memoryId)
            is TravisAction.Collect -> {
                val memory=mutable.value.outing.memories.find { it.id==action.id }
                if(memory==null) announce("This saved place is no longer available.")
                else if(memory.pickup==PickupPhase.NONE) announce("There is no pickup reminder at this place.")
                else if(memory.pickup==PickupPhase.COLLECTED) announce("Already collected · ${memory.title}")
                else { val ownerId=mutable.value.outing.id;invalidate();change(onSaved={if(mutable.value.outing.id==ownerId)announce("Collected · ${memory.title}. Your pickup reminder is complete.")}) { outing ->
                    outing.copy(memories=outing.memories.map { if(it.id==action.id)it.copy(pickup=PickupPhase.COLLECTED)else it })
                } }
            }
            is TravisAction.OpenOuting -> openOuting(action.id)
            is TravisAction.DemoIssue -> if(mutable.value.replay) { issue=action.issue;mutable.update { it.copy(busy=issue=="loading",answer=when(issue){"error"->"Sample error · local model unavailable";"no_match"->"Sample answer · no recorded match";else->""},resultIds=emptyList()) };refresh() }
            is TravisAction.SetReplayHeading -> if(mutable.value.replay) { replayHeading=action.degrees;refresh() }
            is TravisAction.SetTimeReminder -> {
                val state=mutable.value
                when {
                    state.replay->announce("Sample reminder · in ${action.minutes} minutes")
                    state.outing.id.isBlank()->announce("Start or open an outing before setting a reminder.")
                    !state.notificationsAllowed->announce("Allow notifications to receive reminders.")
                    else->try { TravisNotifications.schedule(getApplication(),action.minutes,state.outing.id,state.selectedMemoryId);announce("Reminder requested for about ${action.minutes} minutes from now. Android may delay delivery.") }catch(_:Exception) { announce("The reminder could not be scheduled. Your recording and saved places are unchanged.") }
                }
            }
            TravisAction.CancelInference -> { invalidate();announce("Model request cancelled. Route recording continues.") }
            TravisAction.DismissNotice -> mutable.update { it.copy(notice="") }
            TravisAction.DismissNotification -> mutable.update { it.copy(notificationMemory=null,notificationOutingName="") }
            TravisAction.ImportModel,TravisAction.RequestPermissions -> Unit // Activity owns system launchers.
        }
    }
    fun importModel(uri:Uri) {
        invalidate();mutable.update { it.copy(busy=true,inferenceMessage="Preparing the verified local model…") }
        val importSession=session
        inferenceJob=viewModelScope.launch {
            try {
                modelChangeMutex.withLock {
                    val previous = ownedInference
                    // The lock excludes all new requests until teardown is complete. Scope
                    // cancellation must not strand a closed service or start a second engine.
                    withContext(NonCancellable) { previous?.closeAndJoin() }
                    if (ownedInference === previous) ownedInference = null
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.IO) { models.import(uri) }
                }
                announce("Gemma 4 E2B imported and checksum verified.")
            }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(session==importSession)announce("Model import failed. Use the exact verified Gemma 4 E2B file and check free storage.") }
            finally { mutable.update { it.copy(modelReady=models.ready,
                busy=if(session==importSession)false else it.busy,
                inferenceMessage=if(session==importSession)""else it.inferenceMessage,
            ) } }
        }
    }
    private fun startGuidanceLocation(outingId:String) {
        if(mutable.value.replay || outingId.isBlank())return
        if(!mutable.value.locationAllowed) { announce("Allow precise location to update guidance.");return }
        try { RecordingService.start(getApplication(),outingId) }catch(_:Exception) { announce("Location could not start. Check permission.") }
    }
    private fun advanceMeasuredNavigation(expected:Outing,passed:Int,next:Int) {
        if(navigationAdvancePending || next>=passed || mutable.value.replay)return
        navigationAdvancePending=true
        val navigationSession=session
        viewModelScope.launch {
            try {
                store.mutate(expected.id) { current ->
                    requireNotNull(current)
                    if(currentId!=expected.id || session!=navigationSession || current.guidance.mode!=GuidanceMode.RETRACE || current.guidance!=expected.guidance || ReturnRoute.targetIndex(current)!=passed || next !in current.points.indices || next<(current.guidance.goalIndex?:0) || passed<=(current.guidance.goalIndex?:0))current
                    else if(current.guidance.memoryId!=null)current.copy(guidance=current.guidance.copy(returnIndex=next))
                    else current.copy(retraceIndex=next)
                }
            } catch(_:Exception) { announce("Could not save navigation progress. Check the recorded route on the map.") }
            finally { navigationAdvancePending=false }
        }
    }

    private fun defaultJourneyName(now:Long)=java.text.SimpleDateFormat("MMM d · h:mm a",java.util.Locale.getDefault()).format(java.util.Date(now)).let { "Journey · $it" }
    private fun beginJourney(forceNew:Boolean,name:String="") {
        if(journeyTransition)return
        if(mutable.value.replay) { if(forceNew)announce("Exit Explore to start a real journey.")else change { it.copy(phase=OutingPhase.RECORDING) };return }
        if(!mutable.value.locationAllowed) { announce("Allow precise location, then start your journey.");return }
        if(name.length>80) { announce("Keep the journey name under 80 characters.");return }
        invalidate();journeyTransition=true
        viewModelScope.launch {
            try {
                store.awaitLoaded()
                val existing=store.outings.value.find { it.id==currentId }?:Outing()
                val resume=!forceNew && existing.phase in listOf(OutingPhase.PAUSED,OutingPhase.RECORDING)
                if(forceNew && existing.id.isNotBlank() && existing.phase in listOf(OutingPhase.PAUSED,OutingPhase.RECORDING)) {
                    RecordingService.stop(getApplication())
                    store.mutate(existing.id) { current -> requireNotNull(current).copy(phase=OutingPhase.ENDED,endedAt=System.currentTimeMillis(),guidance=SavedGuidance()) }
                }
                val now=System.currentTimeMillis()
                val outing=if(resume) {
                    store.mutate(existing.id) { current -> requireNotNull(current).copy(phase=OutingPhase.RECORDING,segment=current.segment+1,guidance=SavedGuidance()) }
                    store.outings.value.first { it.id==existing.id }
                } else Outing(UUID.randomUUID().toString(),name.trim().ifBlank { defaultJourneyName(now) },now,OutingPhase.RECORDING).also { store.save(it) }
                currentId=outing.id;preferences.edit().putString("active",outing.id).apply();liveOuting=outing
                RecordingService.start(getApplication(),outing.id)
                mutable.update { it.copy(mode=GuidanceMode.NONE,selectedMemoryId=null,recognizedMemoryId=null,
                    notice=if(resume)"Resumed ${outing.name}. Waiting for a fresh GPS fix."else"New journey started: ${outing.name}. Waiting for a fresh GPS fix.") };refresh()
            } catch(_:Exception) { announce("Could not start the journey. Existing journeys remain saved.") }
            finally { journeyTransition=false }
        }
    }
    private fun renameOuting(id:String,name:String) {
        val clean=name.trim()
        if(clean.isBlank() || clean.length>80) { announce("Use a journey name from 1 to 80 characters.");return }
        if(mutable.value.replay) { announce("Explore is a sample. Rename your real journeys in live mode.");return }
        viewModelScope.launch {
            try { store.mutate(id) { requireNotNull(it).copy(name=clean) };announce("Journey renamed to $clean.") }
            catch(_:Exception) { announce("Could not save that name. Try again.") }
        }
    }

    private fun deleteOuting(id:String) {
        if(mutable.value.replay || store.outings.value.none { it.id==id })return
        if(id==currentId && (photoSaveInFlight || mutable.value.captureBusy || journeyTransition)) {
            announce("Finish saving before deleting this journey.");return
        }
        val deletingActive=id==currentId
        if(deletingActive) { invalidate();RecordingService.stop(getApplication());discardPhotoDraft(true) }
        viewModelScope.launch {
            try {
                val removed=store.delete(id)?:return@launch
                TravisNotifications.cancelOuting(getApplication(),removed)
                if(currentId==id) {
                    currentId=null;preferences.edit().remove("active").apply();liveOuting=Outing()
                    undo.clear();undoContext=null;routeFollower.reset();recentFixes.clear()
                    mutable.update { it.copy(surface=TravisSurface.MAP,assistantMessages=emptyList(),answer="",resultIds=emptyList(),recognizedMemoryId=null,lastKnownFix=null,lastKnownGuidance=false,bearing=null,notificationMemory=null) }
                    refresh()
                }
                announce("Deleted ${removed.name}.")
            } catch(_:Exception) { announce("Could not delete the journey. Try again.") }
        }
    }

    private fun confirmTarget() {
        if(confirmationPending)return
        val snapshot=mutable.value
        if(!snapshot.canConfirm) { announce("Move closer with a fresh GPS fix. Confirmation needs a measured distance within 12 m and bounded GPS uncertainty.");return }
        val expected=snapshot.outing
        invalidate()
        val confirmationSession=session
        if(expected.guidance.mode==GuidanceMode.FIND_LANDMARK) {
            mutable.update { it.copy(recognizedMemoryId=expected.guidance.memoryId) }
            announce("Place confirmed. Mark a pickup collected or continue your return route.");return
        }
        val index=ReturnRoute.targetIndex(expected)?:return
        val goal=expected.guidance.goalIndex?:0
        val next=if(index==goal)null else ReturnRoute.next(expected,index,goal)
        if(index!=goal && next==null) { announce("The recorded segment ends here. No route is inferred across the gap.");return }
        confirmationPending=true
        viewModelScope.launch {
            var applied=false
            try {
                val transform:(Outing)->Outing={ current ->
                    if(current.id!=expected.id || current.guidance!=expected.guidance || ReturnRoute.targetIndex(current)!=index)current
                    else {
                        val updated=if(index==goal)ReturnRoute.completeTarget(current)
                            else if(current.guidance.memoryId!=null)current.copy(guidance=current.guidance.copy(returnIndex=next))
                            else current.copy(retraceIndex=next)
                        applied=updated!=null
                        updated?:current
                    }
                }
                if(snapshot.replay) { replayOuting=transform(replayOuting?:expected);refresh() }
                else store.mutate(expected.id) { current -> current?.let(transform)?:expected }
                if(applied && session==confirmationSession && mutable.value.outing.id==expected.id) {
                    if(index!=goal) {
                        undoContext=ReturnRoute.undoContext(expected);undo.add(index)
                        announce("Waypoint confirmed. Continue to the next point on your recorded route.")
                    } else {
                        undo.clear();undoContext=null
                        if(expected.guidance.memoryId!=null) {
                            mutable.update { it.copy(recognizedMemoryId=expected.guidance.memoryId) }
                            announce("Destination confirmed. Mark any pickup collected, or continue toward your start.")
                        } else {
                            if(!snapshot.replay)RecordingService.stop(getApplication())
                            announce("Back at your recorded start. Journey complete.")
                        }
                    }
                }
            } catch(_:Exception) { announce("Could not save confirmation. Your route progress has not been advanced.") }
            finally { confirmationPending=false }
        }
    }

    private fun takeMeBack(memoryId:String) {
        val outing=mutable.value.outing
        val plan=ReturnRoute.plan(outing,memoryId)
        if(plan==null) { announce("No continuous recorded return segment connects this place to your current progress. Find in AR offers a labelled direct bearing.");return }
        invalidate();undo.clear()
        change { it.copy(phase=OutingPhase.ENDED,endedAt=it.endedAt?:System.currentTimeMillis(),guidance=SavedGuidance(GuidanceMode.RETRACE,memoryId,plan.nextIndex,plan.goalIndex,plan.startIndex,firstIndex=plan.nextIndex)) }
        mutable.update { it.copy(surface=TravisSurface.MAP,captureMode=false,recognizedMemoryId=null,notice="Returning along your recording. The arrow follows the next route point.") }
        startGuidanceLocation(outing.id)
    }
    private fun updateMemoryNote(id:String,note:String) {
        if(note.length>2000) { announce("Keep notes under 2,000 characters.");return }
        invalidate();change { it.copy(revision=it.revision+1,memories=it.memories.map { m->if(m.id==id)m.copy(note=note)else m }) }
    }
    private fun deleteMemory(id:String) {
        invalidate()
        change { ReturnRoute.removeMemory(it,id) }
        mutable.update { it.copy(resultIds=it.resultIds.filterNot { key->key==id },answer="",retryDescriptionId=null) }
        announce("Saved place removed. Recorded route is preserved.")
    }
    private fun openOuting(id:String) {
        val selected=store.outings.value.find { it.id==id }?:return
        if(liveOuting.phase==OutingPhase.RECORDING && LiveSensors.trackingRunning.value && id!=currentId) { announce("Pause the current journey before opening another. Your recording will be preserved.");return }
        invalidate()
        if(id!=currentId)RecordingService.stop(getApplication())
        replayOuting=null;replayFix=null;issue="normal";currentId=id;preferences.edit().putString("active",id).apply();liveOuting=selected
        mutable.update { it.copy(replay=false,surface=TravisSurface.MAP,answer="",resultIds=emptyList()) };refresh()
        if(selected.guidance.mode!=GuidanceMode.NONE)startGuidanceLocation(id)
    }
    fun openNotification(destination:NotificationDestination) {
        viewModelScope.launch {
            store.awaitLoaded()
            val outing=store.outings.value.find { it.id==destination.outingId }
            if(outing==null) { announce("That saved outing is no longer available.");return@launch }
            val memory=outing.memories.find { it.id==destination.memoryId }
            if(liveOuting.id!=outing.id && liveOuting.phase in listOf(OutingPhase.RECORDING,OutingPhase.PAUSED)) {
                mutable.update { it.copy(notificationMemory=memory,notificationOutingName=outing.name,notice=if(memory==null)"Requested reminder for ${outing.name}"else "") }
                return@launch
            }
            invalidate();if(currentId!=outing.id)RecordingService.stop(getApplication());replayOuting=null;currentId=outing.id;preferences.edit().putString("active",outing.id).apply();liveOuting=outing
            mutable.update { it.copy(replay=false,surface=TravisSurface.MAP,answer="",resultIds=listOfNotNull(memory?.id),notice="Requested ${destination.kind} reminder") }
            if(memory!=null && destination.kind !in setOf("departure","recording")) {
                store.mutate(outing.id) { current->ReturnRoute.suspendForFind(requireNotNull(current),memory.id) }
            }
            refresh()
            if(mutable.value.mode!=GuidanceMode.NONE)startGuidanceLocation(outing.id)
        }
    }
    private fun discardPhotoDraft(close:Boolean) {
        if(mutable.value.captureBusy)return
        photoDraft?.photoPath?.takeUnless { it.startsWith("asset:") }?.let { File(it).delete() }
        photoDraft=null;photoDraftOutingId=null
        mutable.update { it.copy(capturedPhotoPath="",captureMode=if(close)false else it.captureMode,surface=if(close)captureOrigin else it.surface,notice="") }
    }
    private fun takePhotoDraft() {
        val state=mutable.value
        if(!state.captureMode || state.captureBusy || photoDraft!=null)return
        if(state.replay) {
            photoDraft=Memory(UUID.randomUUID().toString(),"Saved place",photoPath="asset:demo/stone-bridge.jpg",capturedAt=System.currentTimeMillis(),point=state.fix,routeIndex=state.outing.points.lastIndex)
            photoDraftOutingId=state.outing.id
            mutable.update { it.copy(capturedPhotoPath=photoDraft!!.photoPath) };return
        }
        if(!state.cameraAllowed) { announce("Allow camera access to take a photo.");return }
        val id=UUID.randomUUID().toString();val file=File(store.mediaDirectory,"$id.jpg")
        val captureSession=session;val outingId=state.outing.id
        var location=PhotoLocation(System.currentTimeMillis(),null,null)
        mutable.update { it.copy(captureBusy=true,notice="") }
        camera.takePhoto(file,onCapture={
            val live=mutable.value
            location=PhotoLocation.atDispatch(live.outing,if(live.outing.id==outingId)live.fix else null,System.currentTimeMillis())
        }) { success -> viewModelScope.launch {
            mutable.update { it.copy(captureBusy=false) }
            if(!success) { file.delete();announce("The camera could not take this photo. Try again.");return@launch }
            if(session!=captureSession || mutable.value.outing.id!=outingId || !mutable.value.captureMode) { file.delete();return@launch }
            photoDraft=Memory(id,"Saved place",photoPath=file.absolutePath,capturedAt=location.capturedAt,point=location.point,routeIndex=location.routeIndex)
            photoDraftOutingId=outingId
            mutable.update { it.copy(capturedPhotoPath=file.absolutePath,notice=if(location.point==null)"Photo taken · location was unavailable"else "") }
        } }
    }
    private fun savePhoto(note:String,pickup:Boolean) {
        if(note.length>2000) { announce("Keep notes under 2,000 characters.");return }
        if(mutable.value.captureBusy)return
        val draft=photoDraft?:run { announce("Take a photo first.");return }
        val owner=photoDraftOutingId?:return
        if(owner!=mutable.value.outing.id) { announce("This photo belongs to a different journey. Take it again.");return }
        val memory=draft.copy(title=PlaceNaming.initial(note),note=note,pickup=if(pickup)PickupPhase.WAITING_TO_LEAVE else PickupPhase.NONE)
        if(mutable.value.replay) {
            change { it.copy(memories=it.memories+memory.copy(description="Sample description · saved landmark",indexing="Sample answer"),revision=it.revision+1) }
            photoDraft=null;photoDraftOutingId=null
            mutable.update { it.copy(captureMode=false,capturedPhotoPath="",surface=captureOrigin,notice="Sample place saved to Explore only.") };return
        }
        photoSaveInFlight=true
        mutable.update { it.copy(captureBusy=true) }
        viewModelScope.launch {
            try {
                store.mutate(owner) { requireNotNull(it).let { outing -> outing.copy(memories=outing.memories+memory,revision=outing.revision+1) } }
                photoDraft=null;photoDraftOutingId=null
                photoSaveInFlight=false
                mutable.update { it.copy(captureBusy=false,captureMode=false,capturedPhotoPath="",surface=captureOrigin,notice="${memory.title} saved. Your original photo and note stay on this phone.") }
                describe(memory,owner)
            } catch(cancelled:CancellationException) {
                // Atomic IO may have committed before cancellation returns to Main.
                // Retain the original rather than delete a possibly saved photo.
                throw cancelled
            } catch(_:Exception) { photoSaveInFlight=false;mutable.update { it.copy(captureBusy=false) };announce("Could not save this place. Your photo is ready to retry.") }
        }
    }
    private fun context(checkpoint:String)=RequestContext(session,UUID.randomUUID().toString(),mutable.value.outing.id,mutable.value.outing.revision,checkpoint)
    private fun describe(memory:Memory,outingId:String) {
        if(!models.ready) { announce("Photo saved. Import Gemma to interpret it locally.");return }
        invalidate();val committed=store.outings.value.find { it.id==outingId } ?: return
        val request=RequestContext(session,UUID.randomUUID().toString(),outingId,committed.revision,memory.id);gate.begin(request)
        inferenceJob=viewModelScope.launch {
            inference().describe(request,ImageRef(memory.id,memory.photoPath,memory.capturedAt),memory.note).collect { result->
                if(!gate.accepts(result.context) || currentId!=outingId || store.outings.value.find { it.id==outingId }?.revision!=request.tripRevision || mutable.value.replay) { if(gate.accepts(result.context)) { gate.invalidate();mutable.update { it.copy(busy=false,describingMemoryId=null) } };return@collect }
                when(result) {
                    is InferenceState.Loading -> mutable.update { it.copy(busy=true,inferenceMessage="Reading your photo on this phone…",retryDescriptionId=null,describingMemoryId=memory.id) }
                    is InferenceState.Success -> {
                        if(gate.consume(request)) {
                            fun stillCurrent() = session==request.sessionId && currentId==outingId && !mutable.value.replay
                            try {
                                var accepted = false
                                store.mutate(outingId) { old ->
                                    DescriptionCommit.apply(requireNotNull(old), request, memory.photoPath, result.value).also {
                                        accepted = it.accepted
                                    }.outing
                                }
                                if(stillCurrent()) mutable.update { it.copy(
                                    inferenceMessage=if(accepted)"Gemma 4 · local result"else"Saved places changed. Try interpreting this photo again.",
                                    retryDescriptionId=if(accepted)null else memory.id,
                                ) }
                            } catch(cancelled:CancellationException) { throw cancelled }
                            catch(_:Exception) { if(stillCurrent()) mutable.update { it.copy(
                                inferenceMessage="Photo and route preserved. The model description could not be saved.",retryDescriptionId=memory.id,
                            ) } }
                            finally { if(stillCurrent())mutable.update { it.copy(busy=false,describingMemoryId=null) } }
                        }
                    }
                    is InferenceState.Failure -> { gate.consume(request);mutable.update { it.copy(busy=false,inferenceMessage="Photo preserved. ${result.message}",retryDescriptionId=memory.id,describingMemoryId=null) } }
                }
            }
        }
    }
    override fun onCleared() { if(!photoSaveInFlight)photoDraft?.photoPath?.takeUnless { it.startsWith("asset:") }?.let { File(it).delete() };voice.stop();voiceJob?.cancel();speech?.close();camera.close();ownedInference?.close();ownedInference=null;super.onCleared() }
    private fun ask(query:String) {
        if(query.isBlank()) return
        invalidate();val snapshot=mutable.value;mutable.update { it.copy(assistantMode=AssistantMode.FIND_PLACE,answer="",answerStatus=AssistantAnswerStatus.NONE,resultIds=emptyList(),answerGrounded=null,answerConfidence=null,safetyRefusal=false,inferenceMessage="") }
        if(snapshot.replay) {
            val q=query.lowercase();val ids=snapshot.outing.memories.filter { m-> (q.contains("bottle") && m.id=="demo-bridge") || (q.contains("creek") && m.id=="demo-creek") || ((q.contains("start")||q.contains("car")||q.contains("entrance")) && m.id=="demo-start") }.map { it.id }
            mutable.update { it.copy(resultIds=ids,answerStatus=AssistantAnswerStatus.SAMPLE,answer=if(ids.isEmpty())"Sample answer · no recorded match. Try ‘Where did I leave my bottle?’" else "Sample answer · found a saved memory. Check the source photo before finding it in AR.") };return
        }
        if(!models.ready) { mutable.update { it.copy(answer="Local model unavailable. Import the verified model; your saved memories remain browsable.",answerStatus=AssistantAnswerStatus.UNAVAILABLE,resultIds=emptyList()) };return }
        if(snapshot.outing.memories.isEmpty()) { mutable.update { it.copy(answer="No saved places yet. Capture your first landmark.",answerStatus=AssistantAnswerStatus.INFO) };return }
        val candidates=snapshot.outing.memories.withIndex().toList().takeLast(12).map { (index,m)->Checkpoint(m.id,index,ImageRef(m.id,m.photoPath,m.capturedAt),m.title,m.description.split(" · ").take(8),trackIndex=m.routeIndex,userNotes=m.note) }
        val scopeLabel=if(snapshot.outing.memories.size>12) "Latest 12 of ${snapshot.outing.memories.size} memories. " else ""
        val request=context("retrieval");gate.begin(request)
        inferenceJob=viewModelScope.launch {
            inference().resolveDestination(request,query.take(500),candidates,snapshot.outing,snapshot.fix).collect { result->
                if(!gate.accepts(result.context) || mutable.value.outing.id!=snapshot.outing.id || mutable.value.outing.revision!=request.tripRevision || mutable.value.replay) {
                    if(gate.accepts(result.context)) { gate.invalidate();mutable.update { it.copy(busy=false,answer="Saved memories changed. Ask again to search the current set.",resultIds=emptyList()) } }
                    return@collect
                }
                when(result) {
                    is InferenceState.Loading -> mutable.update { it.copy(busy=true,inferenceMessage="Searching your saved places on this phone…",answer="",resultIds=emptyList(),answerGrounded=null,answerConfidence=null,safetyRefusal=false) }
                    is InferenceState.Success -> if(gate.consume(request)) { val clarification=result.value is DestinationProposal.AskClarification;mutable.update { it.copy(busy=false,answerStatus=AssistantAnswerStatus.LIVE,resultIds=when(val proposal=result.value){is DestinationProposal.Target->listOf(proposal.checkpointId);DestinationProposal.AskClarification->candidates.map { it.id };else->emptyList()},answer=scopeLabel+when(result.value){is DestinationProposal.Target->"Is this the place? Review the original photo and note, then take the recorded route back.";DestinationProposal.AskClarification->"A few saved places could fit. Add a detail or choose from these photos.";DestinationProposal.NoRecordedMatch->"I don't see that in your saved places."},answerGrounded=null,answerConfidence=null,safetyRefusal=false,inferenceMessage=if(clarification)"Gemma 4 · local result · candidates are unconfirmed" else "Gemma 4 · local result") } }
                    is InferenceState.Failure -> { gate.consume(request);mutable.update { it.copy(busy=false,answerStatus=AssistantAnswerStatus.FAILURE,answer=result.message,resultIds=emptyList(),inferenceMessage="") } }
                }
            }
        }
    }

    private fun askAboutTrip(query:String) = chat(query)

    fun stopForegroundVoice() { cancelVoice();speech?.stop() }
    private fun cancelVoice() {
        voice.stop();voiceJob?.cancel();voiceJob=null
        if(mutable.value.voiceProcessing)invalidate()
        mutable.update { it.copy(voiceRecording=false,voiceProcessing=false,voiceStatus="") }
    }
    private fun startVoice() {
        if(mutable.value.busy || mutable.value.voiceRecording || mutable.value.voiceProcessing)return
        if(mutable.value.replay) { announce("Voice is available in your real journey. Explore uses labelled samples.");return }
        if(!models.ready) { announce("Import the local Gemma model before using voice.");return }
        if(getApplication<Application>().checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { announce("Allow microphone access to use voice.");return }
        invalidate()
        val voiceSession=session
        val voiceOuting=mutable.value.outing.id
        val voiceRevision=mutable.value.outing.revision
        fun currentVoice()=session==voiceSession && mutable.value.outing.id==voiceOuting && mutable.value.outing.revision==voiceRevision && !mutable.value.replay
        speech?.stop();voice.prepare()
        mutable.update { it.copy(voiceRecording=true,voiceStatus="Listening · up to 20 seconds. Tap Finish when ready.") }
        voiceJob=viewModelScope.launch {
            try {
                val wav=voice.record()
                if(!currentVoice())return@launch
                mutable.update { it.copy(voiceRecording=false,voiceProcessing=true,voiceStatus="Transcribing on this phone…") }
                val request=context("voice-transcription");gate.begin(request)
                var transcript:String?=null
                inference().transcribe(request,wav).collect { result ->
                    if(!currentVoice() || !gate.accepts(result.context))return@collect
                    when(result) {
                        is InferenceState.Loading -> mutable.update { it.copy(busy=true) }
                        is InferenceState.Success -> if(gate.consume(request))transcript=result.value
                        is InferenceState.Failure -> if(gate.consume(request))mutable.update { it.copy(voiceStatus=result.message) }
                    }
                }
                if(!currentVoice())return@launch
                voiceJob=null
                mutable.update { it.copy(busy=false,voiceProcessing=false) }
                transcript?.let { text -> mutable.update { it.copy(voiceStatus="") };chat(text) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(currentVoice())mutable.update { it.copy(busy=false,voiceRecording=false,voiceProcessing=false,voiceStatus="Could not record that. Check microphone permission and try again.") } }
        }
    }

    private fun chat(query:String) {
        val question=query.trim().take(Question.MAX_QUESTION_CHARS)
        if(question.isBlank() || mutable.value.busy) return
        invalidate()
        val snapshot=mutable.value
        val history=snapshot.assistantMessages.filter {
            it.role==AssistantRole.USER || it.status==AssistantAnswerStatus.LIVE
        }.takeLast(6).map { ConversationTurn(
            if(it.role==AssistantRole.USER) ConversationRole.USER else ConversationRole.TRAVIS,
            it.text,it.sourceIds,
        ) }
        mutable.update { it.copy(
            assistantMode=AssistantMode.TRIP_QUESTION,answer="",answerStatus=AssistantAnswerStatus.NONE,
            resultIds=emptyList(),answerGrounded=null,answerConfidence=null,safetyRefusal=false,
            inferenceMessage="",assistantMessages=(it.assistantMessages+AssistantMessage(
                UUID.randomUUID().toString(),AssistantRole.USER,question)).takeLast(24),
        ) }
        fun reply(text:String,status:AssistantAnswerStatus,ids:List<String> = emptyList(),grounded:Boolean?=null) {
            mutable.update { it.copy(busy=false,answer=text,answerStatus=status,resultIds=ids,
                answerGrounded=grounded,inferenceMessage=if(status==AssistantAnswerStatus.LIVE)"Gemma 4 · on this phone"else "",
                assistantMessages=(it.assistantMessages+AssistantMessage(UUID.randomUUID().toString(),
                    AssistantRole.TRAVIS,text,status,ids)).takeLast(24)) }
        }
        if(snapshot.replay) {
            reply("Sample answer · your bottle is saved at the footbridge. Follow the recorded trail through the creek crossing to get back; choose the saved photo below to begin.",AssistantAnswerStatus.SAMPLE,listOf("demo-bridge"),true)
            return
        }
        if(!models.ready) { reply("The local model is unavailable. Your route and saved photos are still available in Your places.",AssistantAnswerStatus.UNAVAILABLE);return }
        val request=context("conversation");gate.begin(request)
        inferenceJob=viewModelScope.launch {
            try {
                inference().chat(request,question,snapshot.outing,snapshot.fix,history).collect { result ->
                    if(!gate.accepts(result.context))return@collect
                    if(mutable.value.outing.id!=request.tripId || mutable.value.outing.revision!=request.tripRevision || mutable.value.replay) {
                        gate.invalidate();reply("Your saved places changed while I was answering. Ask again to use the updated journey.",AssistantAnswerStatus.INFO);return@collect
                    }
                    when(result) {
                        is InferenceState.Loading -> mutable.update { it.copy(busy=true,inferenceMessage="Reading your journey and saved places…") }
                        is InferenceState.Success -> if(gate.consume(request)) reply(result.value.answer,AssistantAnswerStatus.LIVE,result.value.sourceIds,result.value.grounded)
                        is InferenceState.Failure -> if(gate.consume(request)) reply(result.message,AssistantAnswerStatus.FAILURE)
                    }
                }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(gate.consume(request))reply("Travis could not complete this local request. Try again; your recording is continuing.",AssistantAnswerStatus.FAILURE) }
        }
    }

    private fun askAboutPhoto(memoryId:String,query:String) {
        if(query.isBlank() || mutable.value.busy) return
        invalidate();val snapshot=mutable.value;mutable.update { it.copy(assistantMode=AssistantMode.PHOTO_QUESTION,answer="",answerStatus=AssistantAnswerStatus.NONE,resultIds=emptyList(),answerGrounded=null,answerConfidence=null,safetyRefusal=false,inferenceMessage="") };val memory=snapshot.outing.memories.firstOrNull { it.id==memoryId } ?: return
        if(snapshot.replay) { mutable.update { it.copy(answer="Sample answer · the saved photo shows a wooden footbridge.",answerStatus=AssistantAnswerStatus.SAMPLE,resultIds=listOf(memoryId),answerGrounded=true,answerConfidence="MEDIUM",safetyRefusal=false,inferenceMessage="Explore sample · not a live model result") };return }
        if(!models.ready) { mutable.update { it.copy(answer="Local model unavailable. Import the verified model to ask about this saved photo.",answerStatus=AssistantAnswerStatus.UNAVAILABLE,resultIds=listOf(memoryId),answerGrounded=null) };return }
        val request=RequestContext(session,UUID.randomUUID().toString(),snapshot.outing.id,snapshot.outing.revision,memory.id);gate.begin(request)
        inferenceJob=viewModelScope.launch {
            inference().askAboutPhoto(request,ImageRef(memory.id,memory.photoPath,memory.capturedAt),Question.Text(query.take(Question.MAX_QUESTION_CHARS))).collect { result ->
                if(!gate.accepts(result.context) || mutable.value.outing.id!=request.tripId || mutable.value.outing.revision!=request.tripRevision || mutable.value.replay) { if(gate.accepts(result.context)) { gate.invalidate();mutable.update { it.copy(busy=false) } };return@collect }
                when(result) {
                    is InferenceState.Loading -> mutable.update { it.copy(busy=true,inferenceMessage="Looking at your saved photo on this phone…",answer="",resultIds=listOf(memory.id),answerGrounded=null,answerConfidence=null,safetyRefusal=false) }
                    is InferenceState.Success -> if(gate.consume(request)) mutable.update { it.copy(busy=false,answer=result.value.answer,answerStatus=AssistantAnswerStatus.LIVE,resultIds=listOf(memory.id),answerGrounded=true,answerConfidence=result.value.confidence.name,safetyRefusal=result.value.safetyRefusal,inferenceMessage=if(result.value.safetyRefusal)"" else "Gemma 4 · local result") }
                    is InferenceState.Failure -> { gate.consume(request);mutable.update { it.copy(busy=false,answer=result.message,answerStatus=AssistantAnswerStatus.FAILURE,resultIds=listOf(memory.id),answerGrounded=null,inferenceMessage="") } }
                }
            }
        }
    }

    private fun suggestReminder(memoryId:String) {
        if(mutable.value.busy) return
        val snapshot=mutable.value;val memory=snapshot.outing.memories.firstOrNull { it.id==memoryId } ?: return
        if(memory.note.isBlank()) { announce("Add a note before asking Gemma to interpret a reminder.");return }
        if(snapshot.replay) {
            val task=memory.note.substringAfter("remind me",memory.note).trim().trimStart(':', ' ', 't','o')
            if(task.isNotBlank() && memory.note.contains("remind",ignoreCase=true)) mutable.update { it.copy(pendingReminder=ReminderSuggestion(memory.id,task.take(160)),inferenceMessage="Explore sample · not a live model result") }
            else mutable.update { it.copy(pendingReminder=null,inferenceMessage="") }
            return
        }
        if(!models.ready) { announce("Import the verified local model to interpret this note.");return }
        invalidate();val request=RequestContext(session,UUID.randomUUID().toString(),snapshot.outing.id,snapshot.outing.revision,memory.id);gate.begin(request)
        inferenceJob=viewModelScope.launch {
            inference().parseReminder(request,Question.Text(memory.note.take(Question.MAX_QUESTION_CHARS))).collect { result ->
                if(!gate.accepts(result.context) || mutable.value.outing.id!=request.tripId || mutable.value.outing.revision!=request.tripRevision || mutable.value.replay) { if(gate.accepts(result.context)) { gate.invalidate();mutable.update { it.copy(busy=false,pendingReminder=null) } };return@collect }
                when(result) {
                    is InferenceState.Loading -> mutable.update { it.copy(busy=true,inferenceMessage="Checking whether your note asks for a return reminder…",pendingReminder=null,reminderMessageMemoryId=memory.id) }
                    is InferenceState.Success -> if(gate.consume(request)) mutable.update { it.copy(busy=false,pendingReminder=result.value.takeIf { it.remindOnReturn }?.let { ReminderSuggestion(memory.id,it.task) },inferenceMessage="",reminderMessageMemoryId=null) }
                    is InferenceState.Failure -> { gate.consume(request);mutable.update { it.copy(busy=false,pendingReminder=null,inferenceMessage=result.message,reminderMessageMemoryId=memory.id) } }
                }
            }
        }
    }

    private fun confirmReminder() {
        val suggestion=mutable.value.pendingReminder ?: return
        change { outing->outing.copy(memories=outing.memories.map { if(it.id==suggestion.memoryId && it.pickup==PickupPhase.NONE)it.copy(pickup=PickupPhase.WAITING_TO_LEAVE,pickupTask=suggestion.task)else it }) }
        mutable.update { it.copy(pendingReminder=null) }
        announce("Return reminder added for: ${suggestion.task}")
    }

    private fun updateMemory(id:String,title:String,description:String,note:String?=null) {
        invalidate()
        val cleanTitle=title.trim().take(80);val cleanDescription=description.trim().take(1000)
        if(cleanTitle.isBlank()) { announce("Add a short place name.");return }
        change { outing->outing.copy(revision=outing.revision+1,memories=outing.memories.map { if(it.id==id)it.copy(title=cleanTitle,titleEdited=true,note=note?.take(2000)?:it.note,description=cleanDescription,indexing=if(cleanDescription!=it.description)"Edited by you"else it.indexing)else it }) }
    }

    private fun compareCurrentPhoto(memoryId:String) {
        if(mutable.value.busy) return
        val before=mutable.value
        val expectedIndex=before.routeTargetIndex
        val compareMode=before.mode
        if(compareMode==GuidanceMode.NONE) { announce("Choose a saved place before comparing it.");return }
        val memory=before.outing.memories.firstOrNull { it.id==memoryId &&
            (if(compareMode==GuidanceMode.RETRACE)it.routeIndex==expectedIndex else it.id==before.selectedMemoryId) }
        if(memory==null) { announce("That photo is not the next saved place on your recorded route.");return }
        if(before.replay) { mutable.update { it.copy(comparisonVerdict="INSUFFICIENT_EVIDENCE",comparisonReasons=emptyList(),inferenceMessage="Explore sample · not a live model result") };return }
        if(!models.ready) { mutable.update { it.copy(comparisonVerdict=null,comparisonReasons=emptyList(),inferenceMessage="Import the verified local model to compare photos.") };return }
        if(!before.cameraAllowed) { announce("Allow camera access to take a current comparison photo.");return }
        invalidate();val snapshot=mutable.value;val outingId=snapshot.outing.id;val revision=snapshot.outing.revision;val captureSession=session
        val currentFile=File(getApplication<Application>().cacheDir,"compare-${UUID.randomUUID()}.jpg")
        mutable.update { it.copy(busy=true,comparisonVerdict=null,comparisonReasons=emptyList(),inferenceMessage="Taking a current photo for comparison…") }
        camera.takePhoto(currentFile) { captured ->
            if(session!=captureSession) { currentFile.delete();return@takePhoto }
            inferenceJob=viewModelScope.launch {
                if(session!=captureSession || currentId!=outingId || mutable.value.mode!=compareMode || mutable.value.routeTargetIndex!=expectedIndex || (compareMode==GuidanceMode.FIND_LANDMARK && mutable.value.selectedMemoryId!=memoryId)) { currentFile.delete();if(session==captureSession)mutable.update { it.copy(busy=false,inferenceMessage="Route progress changed. Compare the next landmark when ready.") };return@launch }
                if(!captured) { currentFile.delete();mutable.update { it.copy(busy=false,inferenceMessage="Couldn't take a comparison photo. The saved place is unchanged.") };return@launch }
                val committed=store.outings.value.find { it.id==outingId }
                if(committed==null || committed.revision!=revision) { currentFile.delete();mutable.update { it.copy(busy=false,inferenceMessage="Saved places changed. Compare again with the current photo.") };return@launch }
                val request=RequestContext(session,UUID.randomUUID().toString(),outingId,revision,memoryId);gate.begin(request)
                try {
                    inference().compare(request,ImageRef(memory.id,memory.photoPath,memory.capturedAt),ImageRef("current-${UUID.randomUUID()}",currentFile.absolutePath,System.currentTimeMillis())).collect { result ->
                        if(!gate.accepts(result.context) || currentId!=outingId || store.outings.value.find { it.id==outingId }?.revision!=revision || mutable.value.mode!=compareMode || mutable.value.routeTargetIndex!=expectedIndex || (compareMode==GuidanceMode.FIND_LANDMARK && mutable.value.selectedMemoryId!=memoryId)) { if(gate.accepts(result.context)) { gate.invalidate();mutable.update { it.copy(busy=false) } };return@collect }
                        when(result) {
                            is InferenceState.Loading -> mutable.update { it.copy(busy=true,inferenceMessage="Comparing with your saved photo on this phone…") }
                            is InferenceState.Success -> if(gate.consume(request)) {
                                val supports=result.value.verdict==Verdict.SUPPORTS_MATCH
                                mutable.update { it.copy(busy=false,comparisonVerdict=result.value.verdict.name,comparisonReasons=if(supports)result.value.matchingFeatures else emptyList(),inferenceMessage="Gemma 4 · local result · not a location fix") }
                            }
                            is InferenceState.Failure -> { gate.consume(request);mutable.update { it.copy(busy=false,comparisonVerdict="ERROR",comparisonReasons=emptyList(),inferenceMessage=result.message) } }
                        }
                    }
                } catch(cancelled:CancellationException) {
                    if(gate.accepts(request)) { gate.invalidate();mutable.update { it.copy(busy=false) } }
                    throw cancelled
                }
                catch(_:Exception) { if(gate.consume(request)) mutable.update { it.copy(busy=false,comparisonVerdict="ERROR",comparisonReasons=emptyList(),inferenceMessage="Comparison failed. Your saved photo and route are unchanged.") } }
                finally { currentFile.delete() }
            }
        }
    }
}
