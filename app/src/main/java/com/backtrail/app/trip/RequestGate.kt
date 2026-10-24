package com.backtrail.app.trip

import com.backtrail.app.contracts.RequestContext

/** Main-thread confined. Route changes, undo and session changes must invalidate pending work. */
class RequestGate {
    private var active: RequestContext? = null

    fun begin(context: RequestContext) { active = context }
    fun accepts(context: RequestContext): Boolean = active == context
    fun invalidate() { active = null }

    /** Consume a final result or confirmation once, after context validation. */
    fun consume(context: RequestContext): Boolean {
        if (!accepts(context)) return false
        invalidate()
        return true
    }
}
