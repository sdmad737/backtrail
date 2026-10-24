package com.backtrail.app.device

import com.backtrail.app.contracts.*
import com.backtrail.app.trip.TrailMath
import kotlin.math.max

/** Consecutive GPS evidence only. Durable notification/completion state lives in Memory. */
class PlaceReminderEvidence {
    private val counts = mutableMapOf<Pair<String, String>, Int>()
    fun reset() = counts.clear()
    fun observe(memory: Memory, fix: TrailPoint?, now: Long, notificationsAllowed: Boolean): Memory {
        val point = memory.point
        val usable = point != null && fix != null && TrailMath.validCoordinates(point) &&
            point.accuracy.isFinite() && point.accuracy > 0 && TrailMath.quality(fix, now) == FixQuality.USABLE
        if (!usable) {
            counts.keys.filter { it.first == memory.id }.toList().forEach(counts::remove)
            return memory
        }
        val distance = TrailMath.distance(fix, point)
        val accuracy = max(fix.accuracy, point.accuracy)
        val next = TrailMath.pickup(memory.pickup, distance, accuracy, true)
        val pickupReady = repeated(memory.id to "pickup:${memory.pickup.name}", next != memory.pickup)
        val pickup = if (pickupReady && (next != PickupPhase.NOTIFIED || notificationsAllowed)) next else memory.pickup
        val arrivalReady = repeated(memory.id to "arrival", memory.arrival == ArrivalPhase.ARMED && accuracy <= 25 && distance + accuracy <= 30.0)
        val arrival = if (arrivalReady && notificationsAllowed) ArrivalPhase.NOTIFIED else memory.arrival
        if (pickup != memory.pickup) counts.remove(memory.id to "pickup:${memory.pickup.name}")
        if (arrival != memory.arrival) counts.remove(memory.id to "arrival")
        return if (pickup == memory.pickup && arrival == memory.arrival) memory else memory.copy(pickup = pickup, arrival = arrival)
    }
    private fun repeated(key: Pair<String, String>, qualifies: Boolean): Boolean {
        if (!qualifies) { counts.remove(key); return false }
        val count = ((counts[key] ?: 0) + 1).coerceAtMost(3)
        counts[key] = count
        return count >= 3
    }
}
