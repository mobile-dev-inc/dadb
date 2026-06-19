/*
 * Copyright (c) 2021 mobile.dev inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package dadb

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Lost-wakeup hang when two threads share one [MessageQueue] (e.g. maestro tunnels gRPC over the
 * dadb connection: a keep-alive reader thread + the test-runner thread):
 *
 *  1. Reader G wins `readLock` and parks in `read()`.
 *  2. Consumer T fails `readLock.tryLock()` and parks in `queueCond.await()` (no timeout).
 *  3. G's `read()` throws (SO_TIMEOUT / reset / EOF), but `signalAll()` ran only after a *successful*
 *     read, so it is skipped.
 *  4. T is never signaled and parks forever — neither the read nor the write timeout covers `await()`.
 *
 * Pure concurrency bug in `take()`, so it's reproduced directly with a controllable `readMessage()`
 * — no emulator, fully deterministic.
 */
class MessageQueueLostWakeupTest {

    private companion object {
        const val READER_ID = 1
        const val CONSUMER_ID = 2
        const val ANY_COMMAND = 42
    }

    /** A queue whose single `read()` blocks until [gate] opens and then fails, like a wedged socket. */
    private class WedgingQueue : MessageQueue<Int>() {
        val readerEntered = CountDownLatch(1)
        val gate = CountDownLatch(1)

        override fun readMessage(): Int {
            readerEntered.countDown()      // G is now inside read(), holding readLock
            gate.await()                   // ...block here until the test releases the wedge
            throw IOException("simulated wedged socket")
        }

        override fun getLocalId(message: Int) = message
        override fun getCommand(message: Int) = ANY_COMMAND
        override fun isCloseCommand(message: Int) = false
    }

    @Test
    fun `a read failure must wake a thread parked in take(), not strand it forever`() {
        val queue = WedgingQueue()
        queue.startListening(READER_ID)
        queue.startListening(CONSUMER_ID)

        // Thread G: acquires readLock, enters read(), and will throw once the gate opens.
        val reader = thread(name = "reader-G") {
            try { queue.take(READER_ID, ANY_COMMAND) } catch (ignore: Throwable) {}
        }
        assertTrue(queue.readerEntered.await(2, TimeUnit.SECONDS), "reader G never entered read()")

        // Thread T: fails to grab readLock (G holds it) and parks in queueCond.await().
        val consumerOutcome = AtomicReference<Throwable?>()
        val consumerReturned = CountDownLatch(1)
        val consumer = thread(name = "consumer-T") {
            try {
                queue.take(CONSUMER_ID, ANY_COMMAND)
            } catch (t: Throwable) {
                consumerOutcome.set(t)
            } finally {
                consumerReturned.countDown()
            }
        }

        // Wait until T is genuinely parked in await(). queueLock is free, and T uses tryLock (never
        // blocking) on readLock, so the only thing that parks T is queueCond.await() — whether that
        // await is untimed (WAITING) or bounded (TIMED_WAITING) is an implementation detail.
        awaitParked(consumer)

        // The wedge "times out": G's read() throws. In the buggy code signalAll() is skipped, so
        // nothing wakes T even though readLock is now free.
        queue.gate.countDown()

        val consumerFinished = consumerReturned.await(3, TimeUnit.SECONDS)

        // Clean up the parked thread so a failing run doesn't leak it.
        if (!consumerFinished) consumer.interrupt()
        reader.join(1000)
        consumer.join(1000)

        assertTrue(
            consumerFinished,
            "Consumer thread T was never woken after the reader's read() failed — it is stranded in " +
                "queueCond.await() (lost wakeup). Neither the read nor the write timeout covers this."
        )
        assertFalse(consumer.isAlive, "consumer thread should have terminated")
    }

    private fun awaitParked(t: Thread) {
        val parked = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (t.state in parked) return
            Thread.sleep(5)
        }
        throw AssertionError("thread ${t.name} never parked in await(); state=${t.state}")
    }
}
