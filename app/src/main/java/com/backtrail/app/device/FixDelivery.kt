package com.backtrail.app.device

/** Main-thread generation gate around a suspended disk write. */
class FixDelivery {
    var generation: Long = 0
        private set

    fun interrupt() { generation++ }

    suspend fun commit(generation: Long, persist: suspend () -> Unit,
                       deliverCommitted: () -> Unit, publish: () -> Unit) {
        if (generation != this.generation) return
        persist()
        // A committed reminder must not be consumed merely because GPS was disabled
        // during the write. Displaying a fix, however, requires the same GPS epoch.
        deliverCommitted()
        if (generation == this.generation) publish()
    }
}
