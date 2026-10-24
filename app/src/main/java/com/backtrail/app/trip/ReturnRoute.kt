package com.backtrail.app.trip

import com.backtrail.app.contracts.*
import kotlin.math.abs

/** Ordered recorded geometry only. Never chooses a crossing by nearest position. */
object ReturnRoute {
    data class Plan(val nextIndex: Int, val goalIndex: Int, val startIndex: Int)

    fun targetIndex(outing: Outing): Int? =
        if (outing.guidance.memoryId != null && outing.guidance.mode == GuidanceMode.RETRACE)
            outing.guidance.returnIndex else outing.retraceIndex

    fun plan(outing: Outing, memoryId: String): Plan? {
        val memory = outing.memories.find { it.id == memoryId } ?: return null
        val goal = memory.routeIndex ?: return null
        if (memory.point == null || goal !in outing.points.indices) return null
        val progress = (if (outing.guidance.mode == GuidanceMode.RETRACE) targetIndex(outing)
            else outing.suspendedGuidance?.returnIndex ?: outing.retraceIndex)?.takeIf { it in outing.points.indices }
        val from = progress ?: outing.points.lastIndex
        if (from !in outing.points.indices || goal > from) return null
        if ((goal..from).any { outing.points[it].segment != outing.points[from].segment }) return null
        // A paused return resumes its known next point, never a newly selected nearest crossing.
        val target = progress ?: next(outing, from, goal) ?: goal
        return Plan(target, goal, from)
    }

    fun next(outing: Outing, from: Int, goal: Int = 0): Int? {
        val points = outing.points
        if (from !in points.indices || goal !in points.indices || from <= goal) return null
        var travelled = 0.0
        val photos = outing.memories.mapNotNull { it.routeIndex }.toSet()
        for (i in from - 1 downTo goal) {
            if (points[i].segment != points[from].segment) return null
            travelled += TrailMath.distance(points[i], points[i + 1])
            if (i == goal || i in photos || points[i - 1].segment != points[i].segment) return i
            // Retain recorded bends even when they occur inside a short look-ahead window.
            val incoming = TrailMath.bearing(points[i + 1], points[i])
            val outgoing = TrailMath.bearing(points[i], points[i - 1])
            val legs = TrailMath.distance(points[i + 1], points[i]) >= 1 &&
                TrailMath.distance(points[i], points[i - 1]) >= 1
            if (legs && abs(TrailMath.delta(outgoing, incoming)) >= 35) return i
            if (travelled >= 25) return i
        }
        return null
    }

    fun remaining(outing: Outing, fix: TrailPoint?): Double? {
        val next = targetIndex(outing) ?: return null
        val goal = if (outing.guidance.memoryId != null) outing.guidance.goalIndex ?: return null else 0
        if (goal !in outing.points.indices || next !in outing.points.indices || goal > next || fix == null) return null
        if ((goal..next).any { outing.points[it].segment != outing.points[next].segment }) return null
        return TrailMath.distance(fix, outing.points[next]) + TrailMath.length(outing.points.subList(goal, next + 1))
    }
}
