package com.lamplitisles.herdrmobile.ssh

import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Test

class JschTerminalReaderTest {
    @Test
    fun remoteEofShutsReaderExecutorAndExplicitShutdownRemainsSafe() {
        val executor = Executors.newSingleThreadExecutor()
        val closed = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val reader = JschTerminalReader(
            remoteOutput = ByteArrayInputStream(ByteArray(0)),
            callbacks = NoopTerminalCallbacks,
            closed = closed,
            onFinished = { finished.countDown() },
            reader = executor,
        )

        try {
            reader.start()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(awaitShutdown(executor))

            reader.shutdownNow()
            reader.shutdownNow()
            assertTrue(executor.isShutdown)
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        } finally {
            reader.shutdownNow()
        }
    }

    private fun awaitShutdown(executor: ExecutorService): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (executor.isShutdown) return true
            Thread.yield()
        }
        return executor.isShutdown
    }

    private object NoopTerminalCallbacks : TerminalCallbacks {
        override fun onFrame(data: ByteArray) = Unit
        override fun onClosed(reason: String?) = Unit
    }
}
