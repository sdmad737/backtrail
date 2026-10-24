package com.backtrail.app.ai

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class NativeResourceCleanupTest {
    @Test(timeout = 5_000) fun `close returns while native release blocks on a worker`() = runBlocking {
        val entered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val caller = Thread.currentThread()
        var nativeThread: Thread? = null
        val cleanup = NativeResourceCleanup(Mutex()) {
            nativeThread = Thread.currentThread()
            entered.countDown()
            check(allowRelease.await(3, TimeUnit.SECONDS))
        }
        try {
            cleanup.close()
            assertTrue(cleanup.isClosed)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNotSame(caller, nativeThread)
        } finally {
            allowRelease.countDown()
        }
        cleanup.closeAndJoin()
    }

    @Test(timeout = 5_000) fun `close waits for active native request and releases exactly once`() = runBlocking {
        val mutex = Mutex(locked = true)
        val releases = AtomicInteger()
        val cleanup = NativeResourceCleanup(mutex) { releases.incrementAndGet() }
        cleanup.close()
        cleanup.close()
        assertTrue(cleanup.isClosed)
        assertEquals(0, releases.get())
        mutex.unlock()
        cleanup.closeAndJoin()
        cleanup.closeAndJoin()
        assertEquals(1, releases.get())
        assertFalse(mutex.isLocked)
    }

    @Test(timeout = 5_000) fun `failed release is reported to replacement and cannot strand the lock`() = runBlocking {
        val mutex = Mutex()
        val cleanup = NativeResourceCleanup(mutex) { error("native close failed") }
        try {
            cleanup.closeAndJoin()
            fail("The replacement must see failed shutdown")
        } catch (expected: IllegalStateException) {
            assertEquals("native close failed", expected.message)
        }
        assertTrue(cleanup.isClosed)
        assertFalse(mutex.isLocked)
    }
}
