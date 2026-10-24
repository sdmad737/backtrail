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

class TravisViewModel(app:Application):AndroidViewModel(app) {
    private val store=OutingStore.get(app)
    private val models=ModelStore(app)
    private val inference by lazy { val (file,backend)=models.preferred?:(models.file to InferenceBackend.GPU);LocalVisionService(file,app.cacheDir,backend,nativeLibraryDir=app.applicationInfo.nativeLibraryDir) }
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
    init {
        viewModelScope.launch { store.outings.collect { items -> liveOuting=items.find { it.id==currentId } ?: Outing();refresh() } }
        viewModelScope.launch { combine(LiveSensors.fix,LiveSensors.heading,LiveSensors.headingReliable,LiveSensors.failure) { _,_,_,_->Unit }.collect { refresh() } }
        viewModelScope.launch { store.loadError.filterNotNull().collect { announce(it) } }
        viewModelScope.launch { while(isActive) { delay(1000);refresh() } }
        refreshPermissions()
    }
    fun refreshPermissions() {
        val app=getApplication<Application>()
        mutable.update { it.copy(cameraAllowed=app.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED,
            locationAllowed=app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED,
            notificationsAllowed=TravisNotifications.allowed(app),modelReady=models.ready) }
    }
    private fun refresh() {
        val old=mutable.value
        if(!old.replay) LiveSensors.guidanceMode.value=old.mode
        val storedOuting=replayOuting ?: liveOuting
        val outing=if(!old.replay && storedOuting.phase==OutingPhase.RECORDING && !LiveSensors.trackingRunning.value) storedOuting.copy(phase=OutingPhase.PAUSED) else storedOuting
        val fix=if(old.replay) replayFix else LiveSensors.fix.value
        val quality=if(old.replay) when(issue) {"weak_gps"->FixQuality.APPROXIMATE;"stale_gps"->FixQuality.STALE;else->if(fix==null) FixQuality.UNAVAILABLE else FixQuality.USABLE} else TrailMath.quality(fix,System.currentTimeMillis())
        val memory=outing.memories.find { it.id==old.selectedMemoryId }
        val target=when(old.mode) {
            GuidanceMode.FIND_LANDMARK -> memory?.point
            GuidanceMode.RETRACE -> outing.retraceIndex?.let { outing.points.getOrNull(it) }
            else -> null
        }
        val nearestMemory=if(old.mode==GuidanceMode.RETRACE) outing.memories.firstOrNull { it.routeIndex==outing.retraceIndex } else memory
        val usable=quality==FixQuality.USABLE
        mutable.value=old.copy(outing=outing,savedOutings=store.outings.value,fix=fix,fixQuality=quality,
            heading=if(old.replay) replayHeading else LiveSensors.heading.value,
            headingReliable=if(old.replay) issue!="weak_gps" else LiveSensors.headingReliable.value,
            target=target,targetTitle=if(old.mode==GuidanceMode.RETRACE) nearestMemory?.title?:"Next recorded waypoint" else memory?.title?:"Choose a saved place",
            targetPhoto=nearestMemory?.photoPath?:"",bearing=if(usable && fix!=null && target!=null) TrailMath.bearing(fix,target) else null,
            distanceMeters=if(usable && fix!=null && target!=null) TrailMath.distance(fix,target) else null,
            remainingMeters=if(old.mode==GuidanceMode.RETRACE && outing.retraceIndex!=null) if(usable && fix!=null && target!=null) TrailMath.length(outing.points.take(outing.retraceIndex+1))+TrailMath.distance(fix,target) else null else null,
            canConfirm=if(old.replay) usable && fix!=null && target!=null && TrailMath.distance(fix,target)<20 else TrailMath.canConfirm(fix,target,System.currentTimeMillis()),
            sensorStatus=if(old.replay) "" else LiveSensors.failure.value,
            mapAvailable=issue!="missing_maps" && fix?.let { it.latitude in 37.549..37.585 && it.longitude in -122.390..-122.345 }==true)
    }
    fun announce(text:String) { mutable.update { it.copy(notice=text) } }
    private fun change(block:(Outing)->Outing) {
        if(mutable.value.replay) { replayOuting=block(replayOuting?:ExploreFixture.outing());refresh();return }
        val id=currentId ?: return
        viewModelScope.launch { try { store.mutate(id) { block(it ?: liveOuting) } } catch(_:Exception) { announce("Could not save this change. Original records remain available.") } }
    }
    private fun invalidate() { gate.invalidate();inferenceJob?.cancel();mutable.update { it.copy(busy=false) };session=UUID.randomUUID().toString() }
    fun action(action:TravisAction) {
        when(action) {
            TravisAction.Start -> {
                if(mutable.value.replay) { change { it.copy(phase=OutingPhase.RECORDING) };return }
                if(!mutable.value.locationAllowed) { announce("Allow precise location, then start your outing.");return }
                invalidate()
                viewModelScope.launch {
                    try {
                        val existing=liveOuting
                        val outing=if(existing.phase in listOf(OutingPhase.PAUSED,OutingPhase.RECORDING)) existing.copy(phase=OutingPhase.RECORDING,segment=existing.segment+1) else Outing(UUID.randomUUID().toString(),"Today's outing",System.currentTimeMillis(),OutingPhase.RECORDING)
                        currentId=outing.id;preferences.edit().putString("active",outing.id).apply();store.save(outing);RecordingService.start(getApplication(),outing.id)
                        mutable.update { it.copy(mode=GuidanceMode.NONE,selectedMemoryId=null,notice="Recording starts when a fresh GPS fix arrives.") };refresh()
                    } catch(_:Exception) { announce("Could not start recording. Check location permission.") }
                }
            }
            TravisAction.Pause -> change { it.copy(phase=OutingPhase.PAUSED,segment=it.segment+1) }
            TravisAction.End -> { invalidate();change { it.copy(phase=OutingPhase.ENDED) };if(!mutable.value.replay) RecordingService.stop(getApplication());announce("Outing saved on this phone.") }
            TravisAction.HeadBack -> {
                invalidate();val outing=mutable.value.outing
                if(outing.points.size<2) { announce("Record at least two measured points before retracing.");return }
                if(!mutable.value.replay && !mutable.value.locationAllowed) { announce("Allow precise location to begin retracing.");return }
                val index=outing.retraceIndex ?: TrailMath.previousTarget(outing.points,outing.points.lastIndex)
                if(index==null) { announce("A recording gap separates these points. No continuous retrace is available.");return }
                undo.clear();change { it.copy(phase=OutingPhase.ENDED,retraceIndex=index) }
                mutable.update { it.copy(mode=GuidanceMode.RETRACE,captureMode=false,notice="Following the recorded route in reverse order.") }
                if(!mutable.value.replay) try { RecordingService.start(getApplication(),outing.id) } catch(_:Exception) { announce("Could not start location for retracing.") }
                refresh()
            }
            TravisAction.Confirm -> {
                if(!mutable.value.canConfirm) { announce("A recent, accurate fix near this target is required.");return }
                invalidate()
                if(mutable.value.mode==GuidanceMode.FIND_LANDMARK) {
                    announce("Place recognized. Resume retracing whenever you're ready.");return
                }
                val outing=mutable.value.outing;val index=outing.retraceIndex?:return
                val previous=TrailMath.previousTarget(outing.points,index)
                if(index==0) { change { it.copy(retraceIndex=null) };mutable.update { it.copy(mode=GuidanceMode.NONE) };if(!mutable.value.replay)RecordingService.stop(getApplication());announce("Back at your recorded start. Outing complete.") }
                else if(previous==null) { announce("Recorded segment ends here. There is a gap before the earlier segment; no route is inferred across it.") }
                else { undo.add(index);change { it.copy(retraceIndex=previous) } }
            }
            TravisAction.Undo -> { invalidate();if(undo.isNotEmpty()) { val index=undo.removeAt(undo.lastIndex);change { it.copy(retraceIndex=index) } } }
            TravisAction.ResumeRetrace -> { invalidate();mutable.update { it.copy(mode=if(it.outing.retraceIndex!=null) GuidanceMode.RETRACE else GuidanceMode.NONE,selectedMemoryId=null,notice="") };refresh() }
            TravisAction.StopGuidance -> { invalidate();mutable.update { it.copy(mode=GuidanceMode.NONE,selectedMemoryId=null,surface=TravisSurface.MAP) };if(!mutable.value.replay && mutable.value.outing.phase==OutingPhase.ENDED)RecordingService.stop(getApplication());refresh() }
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
                if(mutable.value.outing.id.isBlank()) { announce("Start an outing before saving a place.");return }
                mutable.update { it.copy(surface=TravisSurface.AR,captureMode=true) }
            }
            TravisAction.CancelCapture -> mutable.update { it.copy(captureMode=false) }
            is TravisAction.SavePhoto -> savePhoto(action.note,action.pickup)
            is TravisAction.SwitchSurface -> mutable.update { it.copy(surface=action.surface,captureMode=false) }
            is TravisAction.SelectMemory -> { invalidate();if(mutable.value.outing.memories.none { it.id==action.id }) return
                mutable.update { it.copy(selectedMemoryId=action.id,mode=GuidanceMode.FIND_LANDMARK,surface=TravisSurface.AR,captureMode=false,notice="") };refresh()
                if(mutable.value.target==null) announce("This photo has no recorded location. You can recognize it visually; direction is unavailable.")
                else if(!mutable.value.replay && mutable.value.locationAllowed) try { RecordingService.start(getApplication(),mutable.value.outing.id) } catch(_:Exception) { announce("Location could not start for Find. Check permission.") }
                else if(!mutable.value.replay) announce("Allow precise location to update this Find direction.")
            }
            is TravisAction.Ask -> ask(action.query)
            is TravisAction.Collect -> change { outing->outing.copy(memories=outing.memories.map { if(it.id==action.id && it.pickup!=PickupPhase.NONE)it.copy(pickup=PickupPhase.COLLECTED)else it }) }
            is TravisAction.OpenOuting -> { if(!mutable.value.replay && liveOuting.phase in listOf(OutingPhase.RECORDING,OutingPhase.PAUSED) && action.id!=currentId) { announce("End the active outing before opening another.");return };invalidate();if(mutable.value.replay)action(TravisAction.ExitExplore);currentId=action.id;preferences.edit().putString("active",action.id).apply();liveOuting=store.outings.value.find { it.id==action.id }?:Outing();mutable.update { it.copy(mode=GuidanceMode.NONE,selectedMemoryId=null,answer="",resultIds=emptyList()) };refresh() }
            is TravisAction.DemoIssue -> if(mutable.value.replay) { issue=action.issue;mutable.update { it.copy(busy=issue=="loading",answer=when(issue){"error"->"Sample error · local model unavailable";"no_match"->"Sample answer · no recorded match";else->""},resultIds=emptyList()) };refresh() }
            is TravisAction.SetReplayHeading -> if(mutable.value.replay) { replayHeading=action.degrees;refresh() }
            is TravisAction.SetTimeReminder -> if(mutable.value.replay) announce("Sample reminder · in ${action.minutes} minutes") else if(mutable.value.notificationsAllowed) { TravisNotifications.schedule(getApplication(),action.minutes);announce("Reminder requested for about ${action.minutes} minutes from now. Android may delay delivery.") } else announce("Allow notifications to receive reminders.")
            TravisAction.CancelInference -> { invalidate();announce("Model request cancelled. Route recording continues.") }
            TravisAction.DismissNotice -> mutable.update { it.copy(notice="") }
            TravisAction.ImportModel,TravisAction.RequestPermissions -> Unit // Activity owns system launchers.
        }
    }
    fun importModel(uri:Uri) {
        invalidate();mutable.update { it.copy(busy=true) }
        val importSession=session
        inferenceJob=viewModelScope.launch {
            try { withContext(Dispatchers.IO) { models.import(uri) };announce("Gemma 4 E2B imported and checksum verified.") }
            catch(_:CancellationException) { throw CancellationException() }
            catch(_:Exception) { announce("Model import failed. Use the exact verified Gemma 4 E2B file and check free storage.") }
            finally { if(session==importSession)mutable.update { it.copy(busy=false,modelReady=models.ready) } }
        }
    }
    private fun savePhoto(note:String,pickup:Boolean) {
        if(note.length>2000) { announce("Keep notes under 2,000 characters.");return }
        if(mutable.value.captureBusy) return
        val state=mutable.value;val id=UUID.randomUUID().toString();val time=System.currentTimeMillis()
        val measured=if(state.fixQuality==FixQuality.USABLE)state.fix else null
        if(state.replay) {
            val memory=Memory(id,"Saved place",note,"asset:demo/stone-bridge.jpg",time,measured,state.outing.points.lastIndex,"Sample description · saved landmark","Sample answer",if(pickup)PickupPhase.WAITING_TO_LEAVE else PickupPhase.NONE)
            change { it.copy(memories=it.memories+memory) };mutable.update { it.copy(captureMode=false,selectedMemoryId=id,notice="Sample photo saved to this replay only.") };return
        }
        if(!state.cameraAllowed) { announce("Allow camera access to save a new photo.");return }
        val file=File(store.mediaDirectory,"$id.jpg");mutable.update { it.copy(captureBusy=true) }
        val outingId=state.outing.id
        val captureSession=session
        var location=PhotoLocation(time,null,null)
        camera.takePhoto(file,onCapture={
            val live=mutable.value
            val sameOuting=live.outing.id==outingId && !live.replay
            location=PhotoLocation.atDispatch(if(sameOuting)live.outing else state.outing,
                if(sameOuting)live.fix else null,System.currentTimeMillis())
        }) { success ->
            viewModelScope.launch {
                mutable.update { it.copy(captureBusy=false) }
                if(!success) { announce("Photo could not be saved. Your outing is unchanged.");return@launch }
                val memory=Memory(id,note.lineSequence().firstOrNull()?.take(40)?.ifBlank { "Saved place" }?:"Saved place",note,file.absolutePath,location.capturedAt,location.point,location.routeIndex,pickup=if(pickup)PickupPhase.WAITING_TO_LEAVE else PickupPhase.NONE)
                try { store.mutate(outingId) { (it?:state.outing).let { old->old.copy(memories=old.memories+memory,revision=old.revision+1) } } }
                catch(_:Exception) { announce("Photo exists locally, but its metadata could not be saved.");return@launch }
                if(session==captureSession && currentId==outingId && !mutable.value.replay && mutable.value.captureMode) {
                    mutable.update { it.copy(captureMode=false,notice=if(location.point==null)"Photo saved · location unknown" else "Photo and measured location saved") }
                    describe(memory,outingId)
                }
            }
        }
    }
    private fun context(checkpoint:String)=RequestContext(session,UUID.randomUUID().toString(),mutable.value.outing.id,mutable.value.outing.revision,checkpoint)
    private fun describe(memory:Memory,outingId:String) {
        if(!models.ready) { announce("Photo saved. Import Gemma to interpret it locally.");return }
        invalidate();val committed=store.outings.value.find { it.id==outingId } ?: return
        val request=RequestContext(session,UUID.randomUUID().toString(),outingId,committed.revision,memory.id);gate.begin(request)
        inferenceJob=viewModelScope.launch {
            inference.describe(request,ImageRef(memory.id,memory.photoPath,memory.capturedAt)).collect { result->
                if(!gate.accepts(result.context) || currentId!=outingId || store.outings.value.find { it.id==outingId }?.revision!=request.tripRevision || mutable.value.replay) { if(gate.accepts(result.context)) { gate.invalidate();mutable.update { it.copy(busy=false) } };return@collect }
                when(result) {
                    is InferenceState.Loading -> mutable.update { it.copy(busy=true) }
                    is InferenceState.Success -> {
                        if(gate.consume(request)) { try { store.mutate(outingId) { old->requireNotNull(old).copy(memories=old.memories.map { if(it.id==memory.id && it.photoPath==memory.photoPath)it.copy(description=result.value.fixedFeatures.joinToString(" · "),title=if(it.note.isBlank())result.value.label else it.title,indexing="Gemma 4 · local result")else it }) } } catch(_:Exception) { announce("Photo and route preserved. The model description could not be saved.") } finally { mutable.update { it.copy(busy=false) } } }
                    }
                    is InferenceState.Failure -> { gate.consume(request);mutable.update { it.copy(busy=false) };announce("Photo preserved. ${result.message}") }
                }
            }
        }
    }
    override fun onCleared() { camera.close();inference.close();super.onCleared() }
    private fun ask(query:String) {
        if(query.isBlank()) return
        invalidate();val snapshot=mutable.value
        if(snapshot.replay) {
            val q=query.lowercase();val ids=snapshot.outing.memories.filter { m-> (q.contains("bottle") && m.id=="demo-bridge") || (q.contains("creek") && m.id=="demo-creek") || ((q.contains("start")||q.contains("car")||q.contains("entrance")) && m.id=="demo-start") }.map { it.id }
            mutable.update { it.copy(resultIds=ids,answer=if(ids.isEmpty())"Sample answer · no recorded match. Try ‘Where did I leave my bottle?’" else "Sample answer · found a saved memory. Check the source photo before finding it in AR.") };return
        }
        if(!models.ready) { mutable.update { it.copy(answer="Local model unavailable. Import the verified model; your saved memories remain browsable.",resultIds=emptyList()) };return }
        if(snapshot.outing.memories.isEmpty()) { mutable.update { it.copy(answer="No saved places yet. Capture your first landmark.") };return }
        val candidates=snapshot.outing.memories.takeLast(12).mapIndexed { index,m->Checkpoint(m.id,index,ImageRef(m.id,m.photoPath,m.capturedAt),m.title,m.description.split(" · ").take(8),userNotes=m.note) }
        val scopeLabel=if(snapshot.outing.memories.size>12) "Latest 12 of ${snapshot.outing.memories.size} memories. " else ""
        val request=context("retrieval");gate.begin(request)
        inferenceJob=viewModelScope.launch {
            inference.resolveDestination(request,query.take(500),candidates).collect { result->
                if(!gate.accepts(result.context) || mutable.value.outing.id!=snapshot.outing.id || mutable.value.outing.revision!=request.tripRevision || mutable.value.replay) {
                    if(gate.accepts(result.context)) { gate.invalidate();mutable.update { it.copy(busy=false,answer="Saved memories changed. Ask again to search the current set.",resultIds=emptyList()) } }
                    return@collect
                }
                when(result) {
                    is InferenceState.Loading -> mutable.update { it.copy(busy=true,answer=scopeLabel+"Searching saved memories on this phone…",resultIds=emptyList()) }
                    is InferenceState.Success -> if(gate.consume(request)) { mutable.update { it.copy(busy=false,resultIds=when(val proposal=result.value){is DestinationProposal.Target->listOf(proposal.checkpointId);else->emptyList()},answer=scopeLabel+when(result.value){is DestinationProposal.Target->"Gemma 4 · local result. Review this saved photo.";DestinationProposal.AskClarification->"Several places may fit. Try a more specific detail.";DestinationProposal.NoRecordedMatch->"No recorded match. Your other memories are still available."}) } }
                    is InferenceState.Failure -> { gate.consume(request);mutable.update { it.copy(busy=false,answer=result.message,resultIds=emptyList()) } }
                }
            }
        }
    }
}
