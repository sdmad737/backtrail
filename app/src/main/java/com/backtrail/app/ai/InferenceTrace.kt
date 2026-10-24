package com.backtrail.app.ai

import android.util.Log

/**
 * Phase timings for one local inference request.
 *
 * Cold initialization is reported separately from generation because, with a per-request
 * engine, initialization happens inside every request and is the dominant cost.
 *
 * Carries no image paths, note text, query text or model output: route data is private and
 * must never reach logcat. [line] is pure so a test can assert that.
 */
data class InferenceTrace(
    val op: String,
    val backend: String,
    val modelDigestPrefix: String,
    val runIndex: Int,
    val initMs: Long = UNMEASURED,
    val generateMs: Long = UNMEASURED,
    val validateMs: Long = UNMEASURED,
    val outcome: String = INCOMPLETE,
) {
    fun line(): String = "op=$op backend=$backend model=$modelDigestPrefix run=$runIndex " +
        "initMs=${render(initMs)} generateMs=${render(generateMs)} " +
        "validateMs=${render(validateMs)} outcome=$outcome"

    fun log() {
        Log.i(TAG, line())
    }

    private fun render(value: Long): String = if (value == UNMEASURED) "-" else value.toString()

    companion object {
        const val TAG = "TravisInference"
        const val UNMEASURED = -1L
        const val INCOMPLETE = "incomplete"

        fun elapsedMsSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
    }
}
