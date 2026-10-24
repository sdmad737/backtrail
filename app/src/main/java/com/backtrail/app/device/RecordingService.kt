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
import kotlin.math.max

/** A user-started foreground service, independent of the UI and all inference work. */
class RecordingService:Service(),LocationListener {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var locations:LocationManager
    private lateinit var store:OutingStore
    private var outingId=""
    private val counts=mutableMapOf<String,Int>()
    private var departureCount=0
    private var lastDepartureAlert=0L
    private var lastEvidenceElapsed=0L
    private fun resetEvidence() { counts.clear();departureCount=0;lastEvidenceElapsed=0 }
    override fun onCreate() { super.onCreate(); locations=getSystemService(LocationManager::class.java); store=OutingStore.get(this) }
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        outingId=intent?.getStringExtra("outing") ?: ""
        resetEvidence()
        if(outingId.isBlank()) { stopSelf();return START_NOT_STICKY }
        try {
            startForeground(11,TravisNotifications.notification(this,"Travis · outing active","Location is active. Pause or end in Travis.",true))
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
                LiveSensors.failure.value="Precise location permission is needed for recording.";stopSelf();return START_NOT_STICKY
            }
            locations.removeUpdates(this)
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,1f,this,Looper.getMainLooper())
            LiveSensors.trackingRunning.value=true
            LiveSensors.failure.value=if(locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) "Waiting for a satellite fix" else "Location is disabled in Android settings"
        } catch(error:Exception) { LiveSensors.failure.value="Location could not start: ${error.javaClass.simpleName}";stopSelf() }
        return START_NOT_STICKY
    }
    override fun onProviderDisabled(provider:String) { resetEvidence();LiveSensors.failure.value="GPS is disabled. Recording has a gap." }
    override fun onProviderEnabled(provider:String) { LiveSensors.failure.value="Waiting for a satellite fix" }
    override fun onLocationChanged(location:Location) {
        val now=System.currentTimeMillis()
        val age=(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1_000_000
        if(age !in 0..15_000 || !location.hasAccuracy() || !location.latitude.isFinite() || !location.longitude.isFinite()) { resetEvidence();return }
        val elapsed=location.elapsedRealtimeNanos/1_000_000
        if(location.accuracy !in 0.01f..25f || (lastEvidenceElapsed!=0L && elapsed-lastEvidenceElapsed !in 1..15_000)) resetEvidence()
        lastEvidenceElapsed=elapsed
        val fix=TrailPoint("fix-${location.elapsedRealtimeNanos}",location.latitude,location.longitude,location.accuracy,location.time,0)
        LiveSensors.fix.value=fix; LiveSensors.failure.value=""
        if(location.accuracy>50 || location.accuracy<=0) return
        val id=outingId
        scope.launch {
            try {
                val newlyNotified=mutableListOf<Memory>()
                store.mutate(id) { old ->
                    val outing=old ?: return@mutate Outing(id=id,startedAt=now)
                    var changed=outing
                    if(outing.phase==OutingPhase.RECORDING) {
                        val last=outing.points.lastOrNull()
                        if(last==null || (fix.time>last.time && (TrailMath.distance(last,fix)>=2 || fix.time-last.time>=10_000))) {
                            val segment=if(last!=null && fix.time-last.time>30_000) outing.segment+1 else outing.segment
                            changed=outing.copy(points=outing.points+fix.copy(segment=segment),segment=segment)
                        }
                    }
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
                val updated=store.outings.value.find { it.id==id } ?: return@launch
                newlyNotified.forEach { memory ->
                    TravisNotifications.post(this@RecordingService,memory.id.hashCode(),"Pickup nearby · ${memory.title}",memory.note.ifBlank { "Check the original photo, then mark collected." })
                }
                val index=updated.retraceIndex
                if(LiveSensors.guidanceMode.value==GuidanceMode.RETRACE && index!=null && index in updated.points.indices && TrailMath.quality(fix,now)==FixQuality.USABLE) {
                    val nearby=updated.points.subList(max(0,index-3),(index+4).coerceAtMost(updated.points.size))
                    val distance=nearby.minOfOrNull { TrailMath.distance(fix,it) } ?: 0.0
                    departureCount=if(distance-fix.accuracy>60) departureCount+1 else if(distance<30) 0 else departureCount
                    if(departureCount>=3 && now-lastDepartureAlert>180_000 && TravisNotifications.post(this@RecordingService,80,"Check your recorded trail","You appear away from the next recorded section. Check the map and saved photo.")) { lastDepartureAlert=now;departureCount=0 }
                }
            } catch(_:Exception) { LiveSensors.failure.value="Could not save location. Recording stopped to preserve data.";stopSelf() }
        }
    }
    override fun onDestroy() { LiveSensors.trackingRunning.value=false;locations.removeUpdates(this);scope.cancel();super.onDestroy() }
    companion object {
        fun start(context:Context,id:String) { context.startForegroundService(Intent(context,RecordingService::class.java).putExtra("outing",id)) }
        fun stop(context:Context) { context.stopService(Intent(context,RecordingService::class.java)) }
    }
}
