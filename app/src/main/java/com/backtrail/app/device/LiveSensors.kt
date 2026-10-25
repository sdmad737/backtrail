package com.backtrail.app.device

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
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
    val headingAccuracyDegrees=MutableStateFlow<Double?>(null)
    val headingStatus=MutableStateFlow("Waiting for compass")
    val mapHeading=MutableStateFlow<Double?>(null)
    val mapHeadingReliable=MutableStateFlow(false)
    val failure=MutableStateFlow("")
}

/** One listener supplies independent rear-camera (-Z) and current display-top bearings. */
class CameraHeading(context: Context) : SensorEventListener {
    private val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    @Suppress("DEPRECATION") // WindowManager.defaultDisplay also works on the supported API 28.
    private val display=(context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    private val filter=CameraBearingFilter()
    private val mapFilter=CameraBearingFilter()
    private val handler=Handler(Looper.getMainLooper())
    private val rotation=FloatArray(9)
    private var started=false
    private var declinationFixId:String?=null
    private var cachedDeclination=0.0
    private var previousDisplayRotation:Int?=null
    private var accuracy=SensorManager.SENSOR_STATUS_UNRELIABLE
    private val expire=Runnable { invalidate("Compass reading is stale") }
    private fun invalidate(reason:String="Waiting for compass") {
        LiveSensors.headingAccuracyDegrees.value=null
        LiveSensors.headingStatus.value=reason
        LiveSensors.heading.value=null
        LiveSensors.headingReliable.value=false
        LiveSensors.mapHeading.value=null
        LiveSensors.mapHeadingReliable.value=false
        filter.reset()
        mapFilter.reset()
        previousDisplayRotation=null
    }
    fun start() {
        if(started)return
        invalidate()
        started=sensor!=null && manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_UI)
        if(!started)LiveSensors.headingStatus.value="Compass sensor is unavailable"
    }
    fun stop() {
        if(!started) { invalidate();return }
        started=false
        manager.unregisterListener(this);handler.removeCallbacks(expire)
        declinationFixId=null
        invalidate()
    }
    override fun onAccuracyChanged(sensor:Sensor?, value:Int) {
        if(!started)return
        accuracy=value
        if(value<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) invalidate("Move away from magnetic interference")
    }
    override fun onSensorChanged(event:SensorEvent) {
        if(!started)return
        accuracy=event.accuracy
        handler.removeCallbacks(expire)
        handler.postDelayed(expire,1_500)
        val fix=LiveSensors.fix.value
        val measurement=HeadingQuality.assess(event.values,accuracy,event.timestamp/1_000_000,SystemClock.elapsedRealtime())
        if(!measurement.usable) {
            invalidate(measurement.reason)
            LiveSensors.headingAccuracyDegrees.value=measurement.accuracyDegrees
            return
        }
        if(TrailMath.quality(fix,System.currentTimeMillis())!=FixQuality.USABLE) {
            invalidate("Waiting for a fresh GPS fix")
            return
        }
        LiveSensors.headingAccuracyDegrees.value=measurement.accuracyDegrees
        LiveSensors.headingStatus.value=measurement.reason
        SensorManager.getRotationMatrixFromVector(rotation,event.values)
        if(declinationFixId!=fix!!.id) {
            cachedDeclination=GeomagneticField(fix.latitude.toFloat(),fix.longitude.toFloat(),0f,System.currentTimeMillis()).declination.toDouble()
            declinationFixId=fix.id
        }
        val elapsed=event.timestamp/1_000_000
        val raw=CameraBearing.fromRotation(rotation,cachedDeclination)
        if(raw==null) {
            filter.reset()
            LiveSensors.heading.value=null
            LiveSensors.headingReliable.value=false
            LiveSensors.headingStatus.value="Point the camera toward the horizon"
        } else {
            LiveSensors.heading.value=filter.accept(raw,elapsed)
            LiveSensors.headingReliable.value=true
        }
        val displayRotation=display.rotation
        if(previousDisplayRotation!=displayRotation) {
            mapFilter.reset()
            previousDisplayRotation=displayRotation
        }
        val mapRaw=CameraBearing.mapFromRotation(rotation,cachedDeclination,displayRotation)
        if(mapRaw==null) {
            mapFilter.reset()
            LiveSensors.mapHeading.value=null
            LiveSensors.mapHeadingReliable.value=false
        } else {
            LiveSensors.mapHeading.value=mapFilter.accept(mapRaw,elapsed)
            LiveSensors.mapHeadingReliable.value=true
        }
    }
}
