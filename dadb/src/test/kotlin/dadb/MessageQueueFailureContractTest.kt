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

import okio.Buffer
import okio.Source
import okio.Timeout
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the failure/close contract of [MessageQueue] that the lost-wakeup fix must preserve:
 *
 *  - a read failure wakes EVERY parked taker (not just the active reader), and surfaces as IOException;
 *  - a read failure is per-call / non-sticky — a transient error does NOT poison the queue, so a later
 *    take() still delivers a subsequently-read message (the regression the sticky-failure latch caused);
 *  - close() wakes every parked taker with a clean IOException rather than stranding them.
 *
 * All deterministic and emulator-free.
 */
class MessageQueueFailureContractTest {

    private companion object {
        const val STREAM = 0
        const val COMMAND = 0
        const val TAKER_COUNT = 4
    }

    /** A queue whose single in-flight read() blocks until [gate] opens, then always fails. */
    private class FailingQueue : MessageQueue<Int>() {
        val readerEntered = CountDownLatch(1)
        val gate = CountDownLatch(1)

        override fun readMessage(): Int {
            readerEntered.countDown()
            gate.await()
            throw IOException("simulated read failure")
        }

        override fun getLocalId(message: Int) = STREAM
        override fun getCommand(message: Int) = COMMAND
        override fun isCloseCommand(message: Int) = false
    }

    @Test
    fun `a read failure wakes every parked taker, and each observes an IOException`() {
        val queue = FailingQueue()
        queue.startListening(STREAM)

        val outcomes = ConcurrentLinkedQueue<Throwable>()
        val done = CountDownLatch(TAKER_COUNT)
        val takers = (0 until TAKER_COUNT).map { i ->
            thread(name = "taker-$i") {
                try {
                    queue.take(STREAM, COMMAND)
                } catch (t: Throwable) {
                    outcomes.add(t)
                } finally {
                    done.countDown()
                }
            }
        }

        // One taker wins readLock and parks inside read(); the rest park in queueCond.await().
        assertTrue(queue.readerEntered.await(2, TimeUnit.SECONDS), "no taker entered read()")
        awaitAllParked(takers)

        // The read fails. Every taker must be woken — the active reader by the throw, the rest by the
        // signalAll() that now runs on failure; each then retries and fails for itself.
        queue.gate.countDown()

        val finished = done.await(3, TimeUnit.SECONDS)
        takers.forEach { it.join(1000) }

        assertTrue(finished, "a read failure stranded one or more parked takers (lost wakeup)")
        assertEquals(TAKER_COUNT, outcomes.size, "every taker should have failed exactly once")
        outcomes.forEach { assertIs<IOException>(it, "failures must surface as IOException") }
    }

    @Test
    fun `a read failure is not sticky - the queue still delivers a later message`() {
        val readCalls = AtomicInteger(0)
        val queue = object : MessageQueue<Int>() {
            // First read fails transiently; the second succeeds and yields a message for STREAM.
            override fun readMessage(): Int {
                if (readCalls.getAndIncrement() == 0) throw IOException("transient read failure")
                return STREAM
            }

            override fun getLocalId(message: Int) = STREAM
            override fun getCommand(message: Int) = COMMAND
            override fun isCloseCommand(message: Int) = false
        }
        queue.startListening(STREAM)

        // The transient failure surfaces to this caller...
        assertFailsWith<IOException> { queue.take(STREAM, COMMAND) }

        // ...but must NOT poison the queue: the next take() reads and returns the message.
        val message = queue.take(STREAM, COMMAND)
        assertEquals(STREAM, message)
        assertEquals(2, readCalls.get(), "second take() should have re-attempted the read")
    }

    @Test
    fun `closing the queue wakes every parked taker with an IOException`() {
        // A real AdbReader over a source that blocks until closed, then fails — mirrors a wedged socket.
        val closed = CountDownLatch(1)
        val blockingSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                closed.await()
                throw IOException("source closed")
            }

            override fun close() {
                closed.countDown()
            }

            override fun timeout(): Timeout = Timeout.NONE
        }
        val queue = AdbMessageQueue(AdbReader(blockingSource))
        queue.startListening(STREAM)

        val outcomes = ConcurrentLinkedQueue<Throwable>()
        val done = CountDownLatch(TAKER_COUNT)
        val takers = (0 until TAKER_COUNT).map { i ->
            thread(name = "adb-taker-$i") {
                try {
                    queue.take(STREAM, Constants.CMD_OKAY)
                } catch (t: Throwable) {
                    outcomes.add(t)
                } finally {
                    done.countDown()
                }
            }
        }
        awaitAllParked(takers)

        queue.close()

        val finished = done.await(3, TimeUnit.SECONDS)
        takers.forEach { it.join(1000) }

        assertTrue(finished, "close() failed to wake one or more parked takers")
        assertEquals(TAKER_COUNT, outcomes.size)
        outcomes.forEach { assertIs<IOException>(it, "close() must surface as IOException") }
    }

    private fun awaitAllParked(threads: List<Thread>) {
        val parked = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (threads.all { it.state in parked }) return
            Thread.sleep(5)
        }
        throw AssertionError("not all takers parked; states=${threads.map { it.state }}")
    }
}
