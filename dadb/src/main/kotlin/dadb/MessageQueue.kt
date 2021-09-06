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

    fun take(localId: Int, command: Int): V {
        while (true) {
            queueLock.lock {
                poll(localId, command)?.let { return it }
                readLock.tryLock ({
                    queueLock.unlock()
                    read()
                    queueLock.lock()
                    queueCond.signalAll()
                }) { queueCond.await() }
            }
        }
    }

    fun startListening(localId: Int) {
        queues.putIfAbsent(localId, ConcurrentHashMap())
    }

    fun stopListening(localId: Int) {
        queues.remove(localId)
    }

    protected abstract fun readMessage(): V

    protected abstract fun getLocalId(message: V): Int

    protected abstract fun getCommand(message: V): Int

    protected abstract fun isCloseCommand(message: V): Boolean

    @TestOnly
    protected fun poll(localId: Int, command: Int): V? {
        val connectionQueues = queues[localId] ?: throw AdbConnectionClosed(localId)
        return connectionQueues[command]?.poll()
    }

    @TestOnly
    protected fun read() {
        val message = readMessage()
        val localId = getLocalId(message)

        if (isCloseCommand(message)) {
            stopListening(localId)
            return
        }

        val connectionQueues = queues[localId] ?: return

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
