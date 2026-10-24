package com.backtrail.app.trip

import com.backtrail.app.contracts.GuidanceMode

/** Evidence belongs to one uninterrupted retrace target; cooldown survives a Find detour. */
class DepartureEvidence {
    var revision = 0L
        private set
    private var mode = GuidanceMode.NONE
    private var target: String? = null
    private var count = 0
    private var lastAlert: Long? = null

    fun modeChanged(next: GuidanceMode) {
        if (mode == next) return
        mode = next
        revision++
        interrupt()
    }

    fun interrupt() { count = 0; target = null }

    fun observe(target: String?, distance: Double, accuracy: Float, usable: Boolean, elapsed: Long): Boolean {
        if (mode != GuidanceMode.RETRACE || target == null || !usable || !distance.isFinite() ||
            !accuracy.isFinite() || accuracy <= 0 || accuracy > 25) {
            interrupt()
            return false
        }
        if (this.target != target) { count = 0; this.target = target }
        count = if (distance - accuracy > 60) count + 1 else if (distance < 30) 0 else count
        return count >= 3 && (lastAlert?.let { elapsed - it > 180_000 } ?: true)
    }

    fun delivered(elapsed: Long) { lastAlert = elapsed; count = 0 }
}
