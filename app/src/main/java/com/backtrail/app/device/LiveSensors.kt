package com.backtrail.app.device

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.backtrail.app.contracts.FixQuality
import com.backtrail.app.contracts.TrailPoint
import com.backtrail.app.trip.TrailMath
import kotlinx.coroutines.flow.MutableStateFlow

object LiveSensors {
    val trackingRunning=MutableStateFlow(false)
    val guidanceMode=MutableStateFlow(com.backtrail.app.contracts.GuidanceMode.NONE)
    val fix=MutableStateFlow<TrailPoint?>(null)
    val heading=MutableStateFlow<Double?>(null)
    val headingReliable=MutableStateFlow(false)
    val failure=MutableStateFlow("")
}

/** Rear camera looks along device -Z, not the top (+Y) used by a flat compass. */
class CameraHeading(context: Context) : SensorEventListener {
    private val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val filter=CameraBearingFilter()
    private val handler=Handler(Looper.getMainLooper())
    private val rotation=FloatArray(9)
    private var started=false
    private var declinationFixId:String?=null
    private var cachedDeclination=0.0
    private var accuracy=SensorManager.SENSOR_STATUS_UNRELIABLE
    private val expire=Runnable { invalidate() }
    private fun invalidate() {
        LiveSensors.heading.value=null
        LiveSensors.headingReliable.value=false
        filter.reset()
    }
    fun start() {
        if(started)return
        invalidate()
        started=sensor!=null && manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_UI)
    }
    fun stop() {
        if(!started)return
        started=false
        manager.unregisterListener(this);handler.removeCallbacks(expire)
        declinationFixId=null
        invalidate()
    }
    override fun onAccuracyChanged(sensor:Sensor?, value:Int) {
        if(!started)return
        accuracy=value
        if(value<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) invalidate()
    }
    override fun onSensorChanged(event:SensorEvent) {
        if(!started)return
        accuracy=event.accuracy
        handler.removeCallbacks(expire)
        handler.postDelayed(expire,1_500)
        val fix=LiveSensors.fix.value
        if(accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM || TrailMath.quality(fix,System.currentTimeMillis())!=FixQuality.USABLE || SystemClock.elapsedRealtime()-event.timestamp/1_000_000 !in 0..1_500) {
            invalidate(); return
        }
        SensorManager.getRotationMatrixFromVector(rotation,event.values)
        if(declinationFixId!=fix!!.id) {
            cachedDeclination=GeomagneticField(fix.latitude.toFloat(),fix.longitude.toFloat(),0f,System.currentTimeMillis()).declination.toDouble()
            declinationFixId=fix.id
        }
        val raw=CameraBearing.fromRotation(rotation,cachedDeclination)
        if(raw==null) { invalidate(); return }
        LiveSensors.heading.value=filter.accept(raw,event.timestamp/1_000_000)
        LiveSensors.headingReliable.value=true
    }
}
