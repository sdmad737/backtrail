package com.backtrail.app.device

import android.content.Context
import android.hardware.*
import com.backtrail.app.contracts.TrailPoint
import com.backtrail.app.trip.TrailMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.*

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
    private var previous: Double?=null
    private var accuracy=SensorManager.SENSOR_STATUS_UNRELIABLE
    fun start() { if(sensor!=null) manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_UI) }
    fun stop() { manager.unregisterListener(this); LiveSensors.heading.value=null; LiveSensors.headingReliable.value=false; previous=null }
    override fun onAccuracyChanged(sensor:Sensor?, value:Int) { accuracy=value }
    override fun onSensorChanged(event:SensorEvent) {
        accuracy=event.accuracy
        val r=FloatArray(9); SensorManager.getRotationMatrixFromVector(r,event.values)
        val east=-r[2].toDouble(); val north=-r[5].toDouble()
        // Near-vertical viewing rays have no useful horizontal direction.
        if(hypot(east,north)<0.25) { LiveSensors.heading.value=null; LiveSensors.headingReliable.value=false; return }
        val fix=LiveSensors.fix.value
        val declination=fix?.let { GeomagneticField(it.latitude.toFloat(),it.longitude.toFloat(),0f,System.currentTimeMillis()).declination.toDouble() } ?: 0.0
        val raw=(Math.toDegrees(atan2(east,north))+declination+360)%360
        val smoothed=previous?.let { (it+0.18*TrailMath.delta(raw,it)+360)%360 } ?: raw
        previous=smoothed; LiveSensors.heading.value=smoothed
        LiveSensors.headingReliable.value=accuracy>=SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM && fix!=null
    }
}
