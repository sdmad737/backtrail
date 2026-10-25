package com.backtrail.app.trip

import com.backtrail.app.contracts.*
import kotlin.math.abs

/** Ordered recorded geometry only. Never chooses a crossing by nearest position. */
object ReturnRoute {
    data class Plan(val nextIndex: Int, val goalIndex: Int, val startIndex: Int)
    data class UndoContext(val outingId: String, val memoryId: String?, val goalIndex: Int, val startIndex: Int)

    data class Step(val index: Int, val distanceFromPreviousMeters: Double)
    data class Itinerary(
        val steps: List<Step>,
        val currentStepIndex: Int,
        val confirmedDistanceMeters: Double,
        val totalDistanceMeters: Double,
        val progress: Float,
        val reachesGoal: Boolean,
    )

    /** The same ordered targets used by navigation, with only user-confirmed progress.
     * Position proximity and nearest-point matching cannot skip steps or complete this bar.
     * A gap yields the reachable segment and reachesGoal=false, never a connecting line.
     */
    fun itinerary(outing: Outing): Itinerary? {
        val guide = outing.guidance
        val completed = completedIndex(outing, guide)
        if (guide.mode != GuidanceMode.RETRACE && completed == null) return null
        val goal = if (guide.memoryId == null) 0 else guide.goalIndex ?: return null
        val start = guide.startIndex ?: outing.points.lastIndex
        val current = completed ?: targetIndex(outing) ?: return null
        if (goal !in outing.points.indices || start !in outing.points.indices || current !in goal..start) return null
        // Persist the first target so confirming a resumed zero-distance first step
        // cannot remove it and renumber every remaining waypoint. Old records keep
        // their departure as a stable first marker until a new return is started.
        val first = guide.firstIndex ?: start
        if (first !in goal..start || current > first ||
            (first..start).any { outing.points[it].segment != outing.points[start].segment }) return null
        val indices = mutableListOf(first)
        var cursor = first
        while (cursor > goal) {
            val following = next(outing, cursor, goal) ?: break
            indices.add(following)
            cursor = following
        }
        // Old saved routes or edits may have a valid cursor between generated targets.
        // Retain that cursor explicitly instead of implying it has been confirmed.
        if (current !in indices) {
            if ((current..start).any { outing.points[it].segment != outing.points[start].segment }) return null
            indices.add(current)
            indices.sortDescending()
        }
        if (indices.isEmpty()) return null
        var previous = start
        val steps = indices.map { index ->
            Step(index, TrailMath.length(outing.points.subList(index, previous + 1))).also { previous = index }
        }
        val active = indices.indexOf(current)
        val total = steps.sumOf { it.distanceFromPreviousMeters }
        val confirmed = if (completed != null) total else steps.take(active).sumOf { it.distanceFromPreviousMeters }
        val progress = if (completed != null) 1f else if (total > 0) (confirmed / total).toFloat().coerceIn(0f, 1f) else 0f
        return Itinerary(steps, active, confirmed, total, progress, indices.last() == goal)
    }

    private fun validIndex(outing: Outing, index: Int?) = index?.takeIf { it in outing.points.indices }

    /** FIND retains returnIndex only after the user confirmed the destination's route point. */
    private fun completedIndex(outing: Outing, guide: SavedGuidance): Int? {
        if (guide.mode != GuidanceMode.FIND_LANDMARK || guide.memoryId == null) return null
        val memory = outing.memories.find { it.id == guide.memoryId } ?: return null
        val index = validIndex(outing, guide.returnIndex) ?: return null
        return index.takeIf { it == guide.goalIndex && it == memory.routeIndex && memory.point != null }
    }

    private fun guideIndex(outing: Outing, guide: SavedGuidance): Int? = when (guide.mode) {
        GuidanceMode.RETRACE -> validIndex(outing, if (guide.memoryId != null) guide.returnIndex else outing.retraceIndex)
        GuidanceMode.FIND_LANDMARK -> completedIndex(outing, guide)
        GuidanceMode.NONE -> null
    }

    private fun knownIndex(outing: Outing): Int? = guideIndex(outing, outing.guidance)
        ?: outing.suspendedGuidance?.let { guideIndex(outing, it) }
        ?: validIndex(outing, outing.retraceIndex)

    fun suspendCurrent(outing: Outing): SavedGuidance? =
        outing.guidance.takeIf { guideIndex(outing, it) != null }
            ?: outing.suspendedGuidance?.takeIf { guideIndex(outing, it) != null }

    fun suspendForFind(outing: Outing, memoryId: String): Outing {
        if (outing.memories.none { it.id == memoryId }) return outing
        return outing.copy(guidance = SavedGuidance(GuidanceMode.FIND_LANDMARK, memoryId),
            suspendedGuidance = suspendCurrent(outing))
    }

    /** Call only after the ordinary measured-arrival/user-confirmation gate succeeds. */
    fun completeTarget(outing: Outing): Outing? {
        if (outing.guidance.mode != GuidanceMode.RETRACE) return null
        val index = validIndex(outing, targetIndex(outing)) ?: return null
        val goal = outing.guidance.goalIndex ?: 0
        if (index != goal) return null
        if (outing.guidance.memoryId == null) {
            if (index != 0) return null
            return outing.copy(retraceIndex = null, guidance = SavedGuidance(), suspendedGuidance = null)
        }
        val memory = outing.memories.find { it.id == outing.guidance.memoryId } ?: return null
        if (memory.routeIndex != index || memory.point == null) return null
        return outing.copy(guidance = outing.guidance.copy(mode = GuidanceMode.FIND_LANDMARK, returnIndex = index))
    }

    private fun continueHome(outing: Outing, confirmed: Int): Outing? {
        if (confirmed == 0) return outing.copy(retraceIndex = null, guidance = SavedGuidance(), suspendedGuidance = null)
        val next = next(outing, confirmed, 0) ?: return null
        return outing.copy(retraceIndex = next, guidance = SavedGuidance(GuidanceMode.RETRACE, startIndex = confirmed, firstIndex = next), suspendedGuidance = null)
    }

    /** Explicit continuation can change the home cursor; merely finding a memory never does. */
    fun resume(outing: Outing): Outing? {
        if (outing.guidance.mode == GuidanceMode.RETRACE) return outing.takeIf { guideIndex(it, it.guidance) != null }
        completedIndex(outing, outing.guidance)?.let { return continueHome(outing, it) }
        outing.suspendedGuidance?.let { suspended ->
            if (suspended.mode == GuidanceMode.RETRACE && guideIndex(outing, suspended) != null)
                return outing.copy(guidance = suspended, suspendedGuidance = null)
            completedIndex(outing, suspended)?.let { return continueHome(outing, it) }
        }
        val home = validIndex(outing, outing.retraceIndex) ?: return null
        return outing.copy(guidance = SavedGuidance(GuidanceMode.RETRACE, startIndex = outing.points.lastIndex, firstIndex = home), suspendedGuidance = null, retraceIndex = home)
    }

    fun canResume(outing: Outing): Boolean = resume(outing) != null

    /** Explicitly choose home, preserving known order instead of restarting at the tail. */
    fun headBack(outing: Outing): Outing? {
        completedIndex(outing, outing.guidance)?.let { return continueHome(outing, it) }
        if (guideIndex(outing, outing.guidance) == null) outing.suspendedGuidance?.let { suspended ->
            completedIndex(outing, suspended)?.let { return continueHome(outing, it) }
        }
        val known = knownIndex(outing)
        val next = known ?: next(outing, outing.points.lastIndex, 0) ?: return null
        return outing.copy(retraceIndex = next, guidance = SavedGuidance(GuidanceMode.RETRACE, startIndex = outing.points.lastIndex, firstIndex = next), suspendedGuidance = null)
    }

    fun undoContext(outing: Outing): UndoContext? {
        if (outing.guidance.mode != GuidanceMode.RETRACE || validIndex(outing, targetIndex(outing)) == null) return null
        val goal = if (outing.guidance.memoryId == null) 0 else outing.guidance.goalIndex ?: return null
        val start = outing.guidance.startIndex ?: outing.points.lastIndex
        if (goal !in outing.points.indices || start !in outing.points.indices || goal > start) return null
        return UndoContext(outing.id, outing.guidance.memoryId, goal, start)
    }

    fun undo(outing: Outing, context: UndoContext?, previousIndex: Int): Outing? {
        val current = validIndex(outing, targetIndex(outing)) ?: return null
        val expected = undoContext(outing) ?: return null
        if (context != expected || previousIndex <= current || previousIndex > expected.startIndex || previousIndex !in outing.points.indices) return null
        if ((current..previousIndex).any { outing.points[it].segment != outing.points[current].segment }) return null
        return if (outing.guidance.memoryId != null) outing.copy(guidance = outing.guidance.copy(returnIndex = previousIndex))
            else outing.copy(retraceIndex = previousIndex)
    }

    fun removeMemory(outing: Outing, id: String): Outing {
        if (outing.memories.none { it.id == id }) return outing
        return outing.copy(revision = outing.revision + 1, memories = outing.memories.filterNot { it.id == id },
            guidance = if (outing.guidance.memoryId == id) SavedGuidance() else outing.guidance,
            suspendedGuidance = outing.suspendedGuidance?.takeUnless { it.memoryId == id })
    }

    fun targetIndex(outing: Outing): Int? =
        if (outing.guidance.memoryId != null && outing.guidance.mode == GuidanceMode.RETRACE)
            outing.guidance.returnIndex else outing.retraceIndex

    fun plan(outing: Outing, memoryId: String): Plan? {
        val memory = outing.memories.find { it.id == memoryId } ?: return null
        val goal = memory.routeIndex ?: return null
        if (memory.point == null || goal !in outing.points.indices) return null
        val progress = knownIndex(outing)
        val from = progress ?: outing.points.lastIndex
        if (from !in outing.points.indices || goal > from) return null
        if ((goal..from).any { outing.points[it].segment != outing.points[from].segment }) return null
        // A paused return resumes its known next point, never a newly selected nearest crossing.
        val confirmed = completedIndex(outing, outing.guidance)
            ?: if (guideIndex(outing, outing.guidance) == null) outing.suspendedGuidance?.let { completedIndex(outing, it) } else null
        val target = if (progress != null && confirmed == null) progress else next(outing, from, goal) ?: goal
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
