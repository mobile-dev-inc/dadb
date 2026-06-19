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

import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

internal abstract class MessageQueue<V> {

    private val readLock = ReentrantLock()
    private val queueLock = ReentrantLock()
    private val queueCond = queueLock.newCondition()
    private val queues = ConcurrentHashMap<Int, ConcurrentHashMap<Int, Queue<V>>>()
    private val openStreams = ConcurrentHashMap<Int, Boolean>().keySet(true)

    // Terminal close marker, guarded by queueLock and set once by signalClosed(). A transient read
    // failure does NOT set this — those stay per-call and retryable. Set only on close (when the
    // socket also closes), so it stays in step with DadbImpl rebuilding the connection on the next op.
    private var closedCause: IOException? = null

    fun take(localId: Int, command: Int): V {
        while (true) {
            queueLock.lock {
                // Closed: fail fast rather than loop into a read on a dead reader. Fresh exception so
                // each caller keeps its own stack.
                closedCause?.let { throw IOException("MessageQueue closed", it) }
                poll(localId, command)?.let { return it }
                readLock.tryLock ({
                    queueLock.unlock() // released so peers can poll/queue while we read
                    try {
                        read()
                    } finally {
                        // Signal on every read() outcome, not just success: a throwing read must still
                        // wake parked takers. The missing signal on this path was the lost-wakeup bug.
                        // Each woken taker retries and sees the failure itself — per-call, not sticky.
                        queueLock.lock()
                        queueCond.signalAll()
                    }
                }) { queueCond.await() }
            }
        }
    }

    /** Mark the queue closed and wake every parked taker so they fail fast. Call before closing the reader. */
    protected fun signalClosed(cause: IOException) {
        queueLock.lock {
            if (closedCause == null) closedCause = cause
            queueCond.signalAll()
        }
    }

    fun startListening(localId: Int) {
        check(openStreams.add(localId)) { "Already listening for localId: $localId" }
        queues.putIfAbsent(localId, ConcurrentHashMap())
    }

    fun stopListening(localId: Int) {
        openStreams.remove(localId)
        queues.remove(localId)
    }

    @TestOnly
    fun ensureEmpty() {
        check(queues.isEmpty()) { "Queues is not empty: ${queues.keys.map { String.format("%X", it) }}" }
        check(openStreams.isEmpty())
    }

    protected abstract fun readMessage(): V

    protected abstract fun getLocalId(message: V): Int

    protected abstract fun getCommand(message: V): Int

    protected abstract fun isCloseCommand(message: V): Boolean

    private fun poll(localId: Int, command: Int): V? {
        val streamQueues = queues[localId] ?: throw AdbStreamClosed(localId)
        val message = streamQueues[command]?.poll()
        if (message == null && !openStreams.contains(localId)) {
            throw AdbStreamClosed(localId)
        }
        return message
    }

    private fun read() {
        val message = readMessage()
        val localId = getLocalId(message)

        if (isCloseCommand(message)) {
            openStreams.remove(localId)
            return
        }

        val streamQueues = queues[localId] ?: return

        val command = getCommand(message)
        val commandQueue = streamQueues.computeIfAbsent(command) { ConcurrentLinkedQueue() }

        commandQueue.add(message)
    }
}

private inline fun <T> ReentrantLock.lock(body: () -> T) {
    lock()
    try {
        body()
    } finally {
        if (isHeldByCurrentThread) unlock()
    }
}

private inline fun ReentrantLock.tryLock(body: () -> Unit, elseBody: () -> Unit) {
    return if (tryLock()) {
        try {
            body()
        } finally {
            if (isHeldByCurrentThread) unlock()
        }
    } else {
        elseBody()
    }
}
