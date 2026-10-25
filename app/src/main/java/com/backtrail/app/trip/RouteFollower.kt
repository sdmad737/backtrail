package com.backtrail.app.trip

import com.backtrail.app.contracts.*
import kotlin.math.*

/** Locally follows an ordered recorded polyline. It proposes navigation progress only:
 * callers must never treat a passed point as recognition, collection or final arrival. */
class RouteFollower {
    enum class Status { NO_ROUTE, ACQUIRING, FOLLOWING, WEAK_GPS, POSITION_UNCERTAIN, OFF_ROUTE, AMBIGUOUS, NEAR_DESTINATION, GAP }
    data class Guidance(
        val status:Status, val target:TrailPoint?=null, val bearing:Double?=null,
        val matchedRouteIndex:Int?=null,val passedTargetIndex:Int?=null,val nextTargetIndex:Int?=null,
        val remainingMeters:Double?=null,val progress:Float?=null,
        val crossTrackMeters:Double?=null,val uncertaintyMeters:Double?=null,
    )
    private data class Identity(val outing:String,val memory:String?,val start:Int,val goal:Int,val first:Int?,val lastPoint:String)
    private data class Projection(val progress:Double,val index:Int,val point:TrailPoint,val crossTrack:Double)
    private var identity:Identity?=null
    private var accepted:Double?=null
    private var lastTime:Long?=null
    private var lastTargetProgress:Double?=null
    private var candidate:Double?=null
    private var acquiring=0
    private var passTarget:Int?=null
    private var passCount=0

    fun reset() {
        identity=null;accepted=null;lastTime=null;lastTargetProgress=null
        candidate=null;acquiring=0;passTarget=null;passCount=0
    }

    fun update(outing:Outing,fix:TrailPoint?,now:Long):Guidance {
        val points=outing.points
        val guide=outing.guidance
        val at=ReturnRoute.targetIndex(outing)
        val start=guide.startIndex ?: points.lastIndex
        val goal=if(guide.memoryId==null)0 else guide.goalIndex
        if(guide.mode!=GuidanceMode.RETRACE || at==null || goal==null ||
            start !in points.indices || goal !in 0..start || at !in goal..start) {
            reset();return Guidance(Status.NO_ROUTE)
        }
        var low=start
        while(low>goal && points[low-1].segment==points[start].segment && validRecorded(points[low]) && validRecorded(points[low-1]))low--
        if(!validRecorded(points[start]))return Guidance(Status.POSITION_UNCERTAIN)
        if(at<low) { reset();return Guidance(Status.GAP) }
        val key=Identity(outing.id,guide.memoryId,start,goal,guide.firstIndex,points[start].id)
        if(identity!=key) { reset();identity=key }
        val lengths=DoubleArray(start-low+1)
        for(i in start-1 downTo low) lengths[start-i]=lengths[start-i-1]+TrailMath.distance(points[i+1],points[i])
        fun progress(index:Int)=lengths[start-index]
        val total=lengths.last()
        val targetProgress=progress(at)
        if(lastTargetProgress?.let { targetProgress+1<it }==true) { reset();identity=key }
        lastTargetProgress=targetProgress
        if(fix==null || TrailMath.quality(fix,now)!=FixQuality.USABLE || fix.accuracy>15 || now-fix.time !in 0..5_000) {
            acquiring=0;candidate=null;passCount=0
            return Guidance(Status.WEAK_GPS)
        }
        val distinct=lastTime!=fix.time
        val dt=lastTime?.let { (fix.time-it)/1000.0 }
        if(dt!=null && dt<0) return Guidance(Status.WEAK_GPS)
        val anchor=accepted ?: max(0.0,targetProgress-30.0)
        // The search never scans the entire outing or jumps to a geometrically close crossing.
        val forward=if(accepted==null)45.0 else (12.0*(dt ?: 1.0).coerceIn(0.0,3.0)+10.0).coerceIn(15.0,45.0)
        val from=(anchor-10.0).coerceAtLeast(0.0)
        val to=(anchor+forward).coerceAtMost(total)
        val projections=mutableListOf<Projection>()
        for(i in start downTo low+1) {
            val a=points[i];val b=points[i-1]
            val begin=progress(i);val end=progress(i-1)
            if(end<from || begin>to || end-begin<.05 || !validRecorded(a) || !validRecorded(b))continue
            val latitude=Math.toRadians((a.latitude+b.latitude+fix.latitude)/3)
            val scaleX=111_195.0*cos(latitude)
            val ax=(a.longitude-fix.longitude)*scaleX;val ay=(a.latitude-fix.latitude)*111_195.0
            val bx=(b.longitude-fix.longitude)*scaleX;val by=(b.latitude-fix.latitude)*111_195.0
            val dx=bx-ax;val dy=by-ay
            val t=(-(ax*dx+ay*dy)/(dx*dx+dy*dy)).coerceIn(
                ((from-begin)/(end-begin)).coerceIn(0.0,1.0),((to-begin)/(end-begin)).coerceIn(0.0,1.0))
            val point=interpolate(a,b,t,"matched",fix.time)
            projections+=Projection(begin+t*(end-begin),i-1,point,hypot(ax+t*dx,ay+t*dy))
        }
        if(projections.isEmpty()) return Guidance(if(total<.05)Status.NEAR_DESTINATION else Status.POSITION_UNCERTAIN)
        val nearest=projections.minBy { it.crossTrack }
        val uncertainty=fix.accuracy.toDouble()+nearest.point.accuracy
        if(nearest.crossTrack>max(10.0,uncertainty)) {
            acquiring=0;candidate=null;passCount=0
            return Guidance(Status.OFF_ROUTE,crossTrackMeters=nearest.crossTrack,uncertaintyMeters=uncertainty)
        }
        val ambiguous=projections.any { other ->
            other.crossTrack<=nearest.crossTrack+max(4.0,fix.accuracy.toDouble()) &&
                abs(other.progress-nearest.progress)>max(20.0,2*TrailMath.distance(other.point,nearest.point)+5)
        }
        if(ambiguous) {
            acquiring=0;candidate=null;passCount=0
            return Guidance(Status.AMBIGUOUS,crossTrackMeters=nearest.crossTrack,uncertaintyMeters=uncertainty)
        }
        if(distinct) {
            val consistent=candidate?.let { abs(nearest.progress-it)<=maxOf(8.0,fix.accuracy.toDouble(),12.0*(dt?:1.0).coerceIn(0.0,3.0)) }==true
            acquiring=if(consistent)(acquiring+1).coerceAtMost(3)else 1
            candidate=nearest.progress
            if(acquiring>=3)accepted=max(accepted?:nearest.progress,nearest.progress)
            lastTime=fix.time
        }
        val measured=accepted ?: nearest.progress
        val matchedIndex=(start downTo low).firstOrNull { progress(it)>=measured } ?: low
        val remaining=(total-measured).coerceAtLeast(0.0)
        val fraction=if(total>.05)(measured/total).toFloat().coerceIn(0f,.999f)else 0f
        val base=Guidance(Status.ACQUIRING,matchedRouteIndex=matchedIndex,
            remainingMeters=remaining,progress=fraction,crossTrackMeters=nearest.crossTrack,uncertaintyMeters=uncertainty)
        // Repeated measured crossing may advance ordinary guidance, including a photo's
        // navigation point. It does not imply the photo was recognized or its item collected.
        if(passTarget!=at) { passTarget=at;passCount=0 }
        val margin=max(3.0,min(8.0,fix.accuracy*.5))
        val passed=at>goal && nearest.progress>targetProgress+margin && nearest.crossTrack<=max(8.0,fix.accuracy.toDouble())
        if(distinct)passCount=if(passed)passCount+1 else 0
        val next=if(passCount>=3 && accepted!=null && acquiring>=3)ReturnRoute.next(outing,at,goal)else null
        val result=base.copy(passedTargetIndex=if(next!=null)at else null,nextTargetIndex=next)
        if(accepted==null || acquiring<3)return result
        if(low==goal && TrailMath.distance(fix,points[goal])<=fix.accuracy+points[goal].accuracy)
            return result.copy(status=Status.NEAR_DESTINATION)
        if(low>goal && TrailMath.distance(fix,points[low])<=fix.accuracy+points[low].accuracy)return result.copy(status=Status.GAP)
        var look=(measured+max(18.0,uncertainty*1.5)).coerceAtMost(total)
        // Keep a genuine corner as a visual target; do not aim diagonally through it.
        for(i in start-1 downTo low+1) {
            val p=progress(i)
            if(p>measured+1.0 && p<look && ReturnRoute.sustainedBend(points,i,start,low,max(6.0,points[i].accuracy*1.5))) {
                look=p;break
            }
        }
        val target=pointAt(points,start,low,lengths,look,fix.time)
        val targetUncertainty=fix.accuracy.toDouble()+target.accuracy
        if(TrailMath.distance(fix,target)<=targetUncertainty)
            return result.copy(status=Status.POSITION_UNCERTAIN,target=target,uncertaintyMeters=targetUncertainty)
        return result.copy(status=Status.FOLLOWING,target=target,bearing=TrailMath.bearing(fix,target),uncertaintyMeters=targetUncertainty)
    }

    private fun validRecorded(point:TrailPoint)=TrailMath.validCoordinates(point) && point.accuracy.isFinite() && point.accuracy>0 && point.accuracy<=25
    private fun interpolate(a:TrailPoint,b:TrailPoint,t:Double,id:String,time:Long)=TrailPoint(
        id,a.latitude+(b.latitude-a.latitude)*t,a.longitude+(b.longitude-a.longitude)*t,
        max(a.accuracy,b.accuracy),time,a.segment)
    private fun pointAt(points:List<TrailPoint>,start:Int,low:Int,lengths:DoubleArray,distance:Double,time:Long):TrailPoint {
        for(i in start downTo low+1) {
            val begin=lengths[start-i];val end=lengths[start-i+1]
            if(end>=distance && end>begin)return interpolate(points[i],points[i-1],((distance-begin)/(end-begin)).coerceIn(0.0,1.0),"route-lookahead-$i",time)
        }
        return points[low]
    }
}
