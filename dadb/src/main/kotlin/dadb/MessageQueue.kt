package dadb

import org.jetbrains.annotations.TestOnly
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

internal abstract class MessageQueue<V> {

    private val readLock = ReentrantLock()
    private val queueLock = ReentrantLock()
    private val queueCond = queueLock.newCondition()
    private val queues = ConcurrentHashMap<Int, ConcurrentHashMap<Int, Queue<V>>>()

    fun take(connectionId: Int, command: Int): V {
        while (true) {
            queueLock.lock {
                poll(connectionId, command)?.let { return it }
                readLock.tryLock ({
                    queueLock.unlock()
                    read()
                    queueLock.lock()
                    queueCond.signalAll()
                }) { queueCond.await() }
            }
        }
    }

    fun startListening(connectionId: Int) {
        queues.putIfAbsent(connectionId, ConcurrentHashMap())
    }

    fun stopListening(connectionId: Int) {
        queues.remove(connectionId)
    }

    protected abstract fun readMessage(): V

    protected abstract fun getConnectionId(message: V): Int

    protected abstract fun getCommand(message: V): Int

    @TestOnly
    protected fun poll(connectionId: Int, command: Int): V? {
        return queues[connectionId]?.get(command)?.poll()
    }

    @TestOnly
    protected fun read() {
        val message = readMessage()

        val connectionId = getConnectionId(message)
        val connectionQueues = queues[connectionId] ?: return

        val command = getCommand(message)
        val commandQueue = connectionQueues.computeIfAbsent(command) { ConcurrentLinkedQueue() }

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
