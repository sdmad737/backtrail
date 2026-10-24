package com.backtrail.app.device

import android.Manifest
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import com.backtrail.app.contracts.*
import com.backtrail.app.trip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.math.max

/** A user-started foreground service, independent of the UI and all inference work. */
class RecordingService:Service(),LocationListener {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var locations:LocationManager
    private lateinit var store:OutingStore
    private var outingId=""
    private sealed interface Event {
        data class Start(val id:String):Event
        data class Fix(val id:String,val location:Location,val generation:Long,val guidanceRevision:Long):Event
        data class Interrupted(val id:String):Event
    }
    private val events=Channel<Event>(Channel.UNLIMITED)
    private var policy=RecordingPolicy()
    private val counts=mutableMapOf<String,Int>()
    private val delivery=FixDelivery()
    private var departure=DepartureEvidence()
    private fun resetEvidence() { counts.clear();departure.interrupt() }
    override fun onCreate() {
        super.onCreate(); locations=getSystemService(LocationManager::class.java); store=OutingStore.get(this)
        scope.launch { LiveSensors.guidanceMode.collect { departure.modeChanged(it) } }
        // One consumer preserves callback order across asynchronous disk writes.
        scope.launch {
            for(event in events) {
                try {
                    when(event) {
                        is Event.Start -> if(event.id==outingId) { policy=RecordingPolicy();counts.clear();departure=DepartureEvidence().also { it.modeChanged(LiveSensors.guidanceMode.value) } }
                        is Event.Interrupted -> if(event.id==outingId) { policy.interrupt();resetEvidence() }
                        is Event.Fix -> if(event.id==outingId) record(event)
                    }
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { LiveSensors.failure.value="Could not save location. Recording stopped to preserve data.";stopSelf();break }
            }
        }
    }
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val requestedId=intent?.getStringExtra("outing") ?: ""
        if(requestedId.isBlank()) { stopSelf();return START_NOT_STICKY }
        try {
            startForeground(11,TravisNotifications.notification(this,"Travis · outing active","Location is active. Pause or end in Travis.",true))
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
                LiveSensors.failure.value="Precise location permission is needed for recording.";stopSelf();return START_NOT_STICKY
            }
            // Switching Map/Find/retrace within the same outing must not reset GPS evidence.
            if(requestedId==outingId && LiveSensors.trackingRunning.value) return START_NOT_STICKY
            delivery.interrupt()
            outingId=requestedId
            events.trySend(Event.Start(outingId))
            LiveSensors.fix.value=null
            locations.removeUpdates(this)
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,this,Looper.getMainLooper())
            LiveSensors.trackingRunning.value=true
            LiveSensors.failure.value=if(locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) "Waiting for a satellite fix" else "Location is disabled in Android settings"
        } catch(error:Exception) { LiveSensors.failure.value="Location could not start: ${error.javaClass.simpleName}";stopSelf() }
        return START_NOT_STICKY
    }
    override fun onProviderDisabled(provider:String) {
        delivery.interrupt()
        LiveSensors.fix.value=null
        events.trySend(Event.Interrupted(outingId))
        LiveSensors.failure.value="GPS is disabled. Recording has a gap."
    }
    override fun onProviderEnabled(provider:String) { LiveSensors.failure.value="Waiting for a satellite fix" }
    override fun onLocationChanged(location:Location) {
        events.trySend(Event.Fix(outingId,Location(location),delivery.generation,departure.revision))
    }
    private suspend fun record(event:Event.Fix) {
        val id=event.id
        val location=event.location
        val now=System.currentTimeMillis()
        val elapsed=SystemClock.elapsedRealtime()
        val sample=RecordingSample(
            TrailPoint("fix-${location.elapsedRealtimeNanos}",location.latitude,location.longitude,if(location.hasAccuracy())location.accuracy else Float.NaN,location.time,0),
            location.elapsedRealtimeNanos/1_000_000,elapsed,now)
        lateinit var observation:RecordingUpdate
        val newlyNotified=mutableListOf<Memory>()
        delivery.commit(event.generation,persist={
        store.mutate(id) { old ->
            val outing=requireNotNull(old) { "Recording outing is missing" }
            observation=policy.accept(outing,sample)
            val fix=observation.fix
            if(observation.evidenceInterrupted || TrailMath.quality(fix,now)!=FixQuality.USABLE) counts.clear()
            val changed=observation.outing
            if(fix==null) return@mutate changed
            // Three consecutive valid samples are required for either leave/arrival transition.
            val memories=changed.memories.map { memory ->
                val point=memory.point ?: return@map memory
                val next=TrailMath.pickup(memory.pickup,TrailMath.distance(fix,point),fix.accuracy,TrailMath.quality(fix,now)==FixQuality.USABLE)
                val key=memory.id+next.name
                if(next==memory.pickup) { counts.keys.filter { it.startsWith(memory.id) }.toList().forEach { counts.remove(it) }; return@map memory }
                val count=(counts[key]?:0)+1;counts[key]=count
                if(count<3 || (next==PickupPhase.NOTIFIED && !TravisNotifications.allowed(this@RecordingService))) return@map memory
                memory.copy(pickup=next).also { if(next==PickupPhase.NOTIFIED)newlyNotified.add(it) }
            }
            changed.copy(memories=memories)
        }
        },deliverCommitted={
            newlyNotified.forEach { memory ->
                TravisNotifications.post(this@RecordingService,memory.id.hashCode(),"Pickup nearby · ${memory.title}",memory.note.ifBlank { "Check the original photo, then mark collected." })
            }
        },publish={
            val fix=observation.fix
            if(observation.evidenceInterrupted || TrailMath.quality(fix,now)!=FixQuality.USABLE) departure.interrupt()
            if(!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LiveSensors.fix.value=null
                policy.interrupt();resetEvidence()
                LiveSensors.failure.value="GPS is disabled. Recording has a gap."
            } else {
                LiveSensors.fix.value=fix
                LiveSensors.failure.value=if(fix==null) "Waiting for a fresh, valid GPS fix" else ""
                val updated=store.outings.value.find { it.id==id }
                val index=updated?.retraceIndex
                if(fix!=null && updated!=null && index!=null && index in updated.points.indices && event.guidanceRevision==departure.revision) {
                    val nearby=updated.points.subList(max(0,index-3),(index+4).coerceAtMost(updated.points.size))
                    val distance=nearby.minOfOrNull { TrailMath.distance(fix,it) } ?: 0.0
                    if(departure.observe("$id:${updated.points[index].id}",distance,fix.accuracy,TrailMath.quality(fix,now)==FixQuality.USABLE,elapsed) &&
                        TravisNotifications.post(this@RecordingService,80,"Check your recorded trail","You appear away from the next recorded section. Check the map and saved photo.")) departure.delivered(elapsed)
                } else departure.interrupt()
            }
        })
    }
    override fun onDestroy() { LiveSensors.trackingRunning.value=false;LiveSensors.fix.value=null;locations.removeUpdates(this);events.close();scope.cancel();super.onDestroy() }
    companion object {
        fun start(context:Context,id:String) { context.startForegroundService(Intent(context,RecordingService::class.java).putExtra("outing",id)) }
        fun stop(context:Context) { context.stopService(Intent(context,RecordingService::class.java)) }
    }
}
