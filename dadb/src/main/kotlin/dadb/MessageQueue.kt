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
    private val queues = ConcurrentHashMap<Long, Queue<V>>()

    fun take(type: Long): V {
        while (true) {
            queueLock.lock {
                poll(type)?.let { return it }
                readLock.tryLock ({
                    queueLock.unlock()
                    read()
                    queueLock.lock()
                    queueCond.signalAll()
                }) { queueCond.await() }
            }
        }
    }

    fun registerType(type: Long) {
        queues.putIfAbsent(type, ConcurrentLinkedQueue())
    }

    fun unregisterType(type: Long) {
        queues.remove(type)
    }

    protected abstract fun readMessage(): V

    protected abstract fun getType(v: V): Long

    @TestOnly
    protected fun poll(type: Long): V? {
        val queue = queues[type] ?: throw IllegalStateException(String.format(Locale.US, "Type has not been registered: %x", type))
        return queue.poll()
    }

    @TestOnly
    protected fun read() {
        val message = readMessage()
        val type = getType(message)
        queues[type]?.add(message)
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
