package com.backtrail.app.ai

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native shutdown may take seconds. Serialize it with requests without parking the UI. */
internal class NativeResourceCleanup(
    private val requestMutex: Mutex,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closing = AtomicBoolean(false)
    private val completed = CompletableDeferred<Unit>()
    val isClosed: Boolean get() = closing.get()

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        // Deliberately independent of the caller's scope: ViewModel cancellation must not
        // abandon native resources. The one job completes when any active request releases.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                requestMutex.withLock { release() }
                completed.complete(Unit)
            } catch (failure: Throwable) {
                completed.completeExceptionally(failure)
            }
        }
    }

    /** Replacement must wait for old native allocations to be released before loading again. */
    suspend fun closeAndJoin() {
        close()
        completed.await()
    }
}
