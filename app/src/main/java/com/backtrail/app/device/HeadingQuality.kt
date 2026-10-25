package com.backtrail.app.device

/** Quality of one TYPE_ROTATION_VECTOR observation, before applying any smoothing.
 * Android values[4] is estimated heading error in radians, or -1 when unknown.
 */
object HeadingQuality {
    data class Reading(val usable:Boolean,val accuracyDegrees:Double?,val reason:String)
    fun assess(values:FloatArray,status:Int,eventElapsedMillis:Long,nowElapsedMillis:Long):Reading {
        val error=values.getOrNull(4)?.takeIf { it.isFinite() && it>=0f }?.let { Math.toDegrees(it.toDouble()) }
        return when {
            nowElapsedMillis-eventElapsedMillis !in 0..1_500 -> Reading(false,error,"Compass reading is stale")
            values.size<3 || values.take(4).any { !it.isFinite() } -> Reading(false,error,"Compass reading is invalid")
            values.getOrNull(4)?.let { !it.isFinite() || it < -1f } == true -> Reading(false,null,"Compass uncertainty is invalid")
            status<2 -> Reading(false,error,"Move away from magnetic interference")
            error!=null && error>25.0 -> Reading(false,error,"Compass uncertainty is too large; move away from magnetic interference")
            else -> Reading(true,error,if(error==null)"Compass accuracy is not reported"else "")
        }
    }
}
