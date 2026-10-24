package com.backtrail.app.trip

import com.backtrail.app.contracts.*
import kotlin.math.*

object TrailMath {
    fun distance(a: TrailPoint, b: TrailPoint): Double {
        val lat = Math.toRadians(b.latitude-a.latitude)
        val lon = Math.toRadians(b.longitude-a.longitude)
        val h = sin(lat/2).pow(2) + cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(lon/2).pow(2)
        return 6_371_000.0*2*atan2(sqrt(h.coerceIn(0.0,1.0)),sqrt((1-h).coerceIn(0.0,1.0)))
    }
    fun bearing(a: TrailPoint, b: TrailPoint): Double {
        val d = Math.toRadians(b.longitude-a.longitude)
        val la = Math.toRadians(a.latitude); val lb = Math.toRadians(b.latitude)
        return (Math.toDegrees(atan2(sin(d)*cos(lb),cos(la)*sin(lb)-sin(la)*cos(lb)*cos(d)))+360)%360
    }
    fun delta(target: Double, heading: Double) = (target-heading+540)%360-180
    fun quality(point: TrailPoint?, now: Long): FixQuality = when {
        point == null -> FixQuality.UNAVAILABLE
        now-point.time > 15_000 || point.time-now > 5_000 -> FixQuality.STALE
        point.accuracy > 25 || point.accuracy <= 0 -> FixQuality.APPROXIMATE
        else -> FixQuality.USABLE
    }
    fun length(points: List<TrailPoint>): Double = points.zipWithNext().sumOf { (a,b) -> if(a.segment==b.segment) distance(a,b) else 0.0 }
    /** Advances only backwards in recorded order; never jumps to a nearby crossing. */
    fun previousTarget(points: List<TrailPoint>, from: Int): Int? {
        if(from<=0 || from>points.lastIndex) return null
        var total=0.0
        for(i in from-1 downTo 0) {
            if(points[i].segment != points[from].segment) return null
            total += distance(points[i],points[i+1])
            if(total>=25 || i==0 || points[i-1].segment!=points[i].segment) return i
        }
        return null
    }
    fun canConfirm(fix: TrailPoint?, target: TrailPoint?, now: Long): Boolean =
        fix!=null && target!=null && quality(fix,now)==FixQuality.USABLE && distance(fix,target)<=max(15.0,fix.accuracy*1.5)
    fun pickup(phase: PickupPhase, distance: Double, accuracy: Float, recent: Boolean): PickupPhase {
        if(!recent || accuracy>25 || accuracy<=0) return phase
        return when(phase) {
            PickupPhase.WAITING_TO_LEAVE -> if(distance>max(60.0,accuracy*3.0)) PickupPhase.ARMED else phase
            PickupPhase.ARMED -> if(distance+accuracy<=30.0) PickupPhase.NOTIFIED else phase
            else -> phase
        }
    }
}
