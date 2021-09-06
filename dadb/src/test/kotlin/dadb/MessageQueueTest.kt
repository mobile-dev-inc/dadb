package dadb

import com.google.common.truth.Truth
import org.junit.Before
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

internal class MessageQueueTest : BaseConcurrencyTest() {

    private val messageQueue = TestMessageQueue()

    @Before
    fun setUp() {
        messageQueue.startListening(0)
        messageQueue.startListening(1)
    }

    @Test
    fun basic() {
        send(0)
        take(0)
    }

    @Test
    fun multipleTypes() {
        send(0)
        send(1)

        take(0)
        take(1)
    }

    @Test
    fun concurrency1() {
        val sendsRemaining = AtomicInteger(1000)
        launch(20) {
            while (true) {
                val remaining = sendsRemaining.decrementAndGet()
                if (remaining < 0) break
                val localId = remaining % 2
                send(localId)
            }
        }

        val takesRemaining = AtomicInteger(1000)
        launch(20) {
            while (true) {
                val remaining = takesRemaining.decrementAndGet()
                if (remaining < 0) break
                val localId = remaining % 2
                take(localId)
            }
        }

        waitForAll()
    }

    @Test
    fun concurrency2() {
        val takesRemaining = AtomicInteger(1000)
        launch(20) {
            while (true) {
                takesRemaining.decrementAndGet()
                take(0)
            }
        }

        send(1)

        launch(1) {
            take(1)
        }[0].waitFor()
    }

    private fun send(localId: Int) {
        messageQueue.sendMessage(localId)
    }

    private fun take(localId: Int) {
        val message = messageQueue.take(localId, 0)
        Truth.assertThat(message).isEqualTo(localId)
    }
}

private class TestMessageQueue : MessageQueue<Int>() {

    private val readQueue = LinkedBlockingDeque<Int>()

    val readCount = AtomicInteger(0)

    fun sendMessage(message: Int) {
        readQueue.add(message)
    }

    override fun readMessage(): Int {
        readCount.incrementAndGet()
        return readQueue.take()
    }

    override fun getLocalId(message: Int) = message

    override fun getCommand(message: Int) = 0

    override fun isCloseCommand(message: Int) = false
}
