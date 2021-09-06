package dadb

import com.google.common.truth.Truth
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

internal class MessageQueueTest {

    @Test
    fun basic() {
        val messageQueue = TestMessageQueue()
        messageQueue.startListening(0)
        messageQueue.sendMessage(0)

        Truth.assertThat(messageQueue.take(0, 0)).isEqualTo(0)
    }

    @Test
    fun multipleTypes() {
        val messageQueue = TestMessageQueue()
        messageQueue.startListening(0)
        messageQueue.startListening(1)
        messageQueue.sendMessage(0)
        messageQueue.sendMessage(1)

        Truth.assertThat(messageQueue.take(0, 0)).isEqualTo(0)
        Truth.assertThat(messageQueue.take(1, 0)).isEqualTo(1)
    }

    @Test
    fun concurrency1() {
        concurrency1({ messageQueue, type -> messageQueue.take(type, 0) }) { messageQueue, futures ->
            Truth.assertThat(messageQueue.readCount.get()).isEqualTo(100)
            waitFor(futures)
        }
    }

    @Test
    fun concurrency1_unsafe1() {
        concurrency1({ messageQueue, type -> messageQueue.takeUnsafe1(type) }) { messageQueue, futures ->
            expectTimeout(futures)
        }
    }

    @Test
    fun concurrency1_unsafe2() {
        concurrency1({ messageQueue, type -> messageQueue.takeUnsafe2(type) }) { messageQueue, futures ->
            expectTimeout(futures)
            Truth.assertThat(messageQueue.readCount.get()).isLessThan(100)
        }
    }

    @Test
    fun concurrency2() {
        concurrency2({ messageQueue, type -> messageQueue.take(type, 0) }) { messageQueue, futures ->
            Truth.assertThat(messageQueue.readCount.get()).isEqualTo(2)
            waitFor(futures)
        }
    }

    @Test
    fun concurrency2_unsafe1() {
        concurrency2({ messageQueue, type -> messageQueue.takeUnsafe1(type) }) { messageQueue, futures ->
            expectTimeout(futures)
            Truth.assertThat(messageQueue.readCount.get()).isGreaterThan(2)
        }
    }

    @Test
    fun concurrency2_unsafe2() {
        concurrency2({ messageQueue, type -> messageQueue.takeUnsafe2(type) }) { messageQueue, futures ->
            expectTimeout(futures)
        }
    }

    private fun concurrency1(
            take: (messageQueue: TestMessageQueue, type: Int) -> Int,
            assertions: (messageQueue: TestMessageQueue, futures: List<CompletableFuture<Void>>) -> Unit
    ) {
        val messageQueue = TestMessageQueue()
        messageQueue.startListening(0)
        messageQueue.startListening(1)

        val futures = listOf(0, 1).flatMap { type ->
            (0 until 50).map {
                CompletableFuture.runAsync {
                    Truth.assertThat(take(messageQueue, type)).isEqualTo(type)
                }
            }
        }

        Thread.sleep(500)

        (0 until 100).forEach { i -> messageQueue.sendMessage(i % 2)}

        Thread.sleep(500)

        assertions(messageQueue, futures)
    }

    private fun concurrency2(
            take: (messageQueue: TestMessageQueue, type: Int) -> Int,
            assertions: (messageQueue: TestMessageQueue, futures: List<CompletableFuture<Void>>) -> Unit
    ) {
        val messageQueue = TestMessageQueue()
        messageQueue.startListening(0)
        messageQueue.startListening(1)

        (0 until 50).map {
            CompletableFuture.runAsync {
                take(messageQueue, 0)
            }
        }

        Thread.sleep(200)

        val future = CompletableFuture.runAsync {
            Truth.assertThat(take(messageQueue, 1)).isEqualTo(1)
        }

        Thread.sleep(200)

        messageQueue.sendMessage(1)

        Thread.sleep(200)

        assertions(messageQueue, listOf(future))
    }

    private fun waitFor(futures: List<CompletableFuture<Void>>) {
        CompletableFuture.allOf(*futures.toTypedArray()).get(5000, TimeUnit.MILLISECONDS)
    }

    private fun expectTimeout(futures: List<CompletableFuture<Void>>) {
        try {
            CompletableFuture.allOf(*futures.toTypedArray()).get(500, TimeUnit.MILLISECONDS)
            Truth.assertWithMessage("Expected TimeoutException").fail()
        } catch (ignore: TimeoutException) {}
    }
}

private class TestMessageQueue : MessageQueue<Int>() {

    private val readQueue = LinkedBlockingDeque<Int>()

    val readCount = AtomicInteger(0)

    fun sendMessage(message: Int) {
        readQueue.add(message)
    }

    fun takeUnsafe1(type: Int): Int {
        while (true) {
            poll(type, 0)?.let { return it }
            read()
        }
    }

    fun takeUnsafe2(type: Int): Int {
        while (true) {
            synchronized(this) {
                poll(type, 0)?.let { return it }
                read()
            }
        }
    }

    override fun readMessage(): Int {
        readCount.incrementAndGet()
        return readQueue.take()
    }

    override fun getLocalId(message: Int) = message

    override fun getCommand(message: Int) = 0

    override fun isCloseCommand(message: Int) = false
}
