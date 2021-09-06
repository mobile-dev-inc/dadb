package dadb

import com.google.common.truth.Truth
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test

internal class MessageQueueTest {

    @Test
    fun basic() {
        val messageQueue = TestMessageQueue()
        messageQueue.registerType(0)
        messageQueue.sendMessage(0)

        Truth.assertThat(messageQueue.take(0)).isEqualTo(0)
    }

    @Test
    fun multipleTypes() {
        val messageQueue = TestMessageQueue()
        messageQueue.registerType(0)
        messageQueue.registerType(1)
        messageQueue.sendMessage(0)
        messageQueue.sendMessage(1)

        Truth.assertThat(messageQueue.take(0)).isEqualTo(0)
        Truth.assertThat(messageQueue.take(1)).isEqualTo(1)
    }

    @Test
    fun concurrency1() {
        concurrency1({ messageQueue, type -> messageQueue.take(type) }) { messageQueue, futures ->
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
        concurrency2({ messageQueue, type -> messageQueue.take(type) }) { messageQueue, futures ->
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
            take: (messageQueue: TestMessageQueue, type: Long) -> Long,
            assertions: (messageQueue: TestMessageQueue, futures: List<CompletableFuture<Void>>) -> Unit
    ) {
        val messageQueue = TestMessageQueue()
        messageQueue.registerType(0)
        messageQueue.registerType(1)

        val futures = listOf(0L, 1L).flatMap { type ->
            (0 until 50).map {
                CompletableFuture.runAsync {
                    Truth.assertThat(take(messageQueue, type)).isEqualTo(type)
                }.orTimeout(2000, TimeUnit.MILLISECONDS)
            }
        }

        Thread.sleep(500)

        (0 until 100).forEach { i -> messageQueue.sendMessage((i % 2).toLong())}

        Thread.sleep(500)

        assertions(messageQueue, futures)
    }

    private fun concurrency2(
            take: (messageQueue: TestMessageQueue, type: Long) -> Long,
            assertions: (messageQueue: TestMessageQueue, futures: List<CompletableFuture<Void>>) -> Unit
    ) {
        val messageQueue = TestMessageQueue()
        messageQueue.registerType(0)
        messageQueue.registerType(1)

        (0 until 50).map {
            CompletableFuture.runAsync {
                take(messageQueue, 0)
            }.orTimeout(500, TimeUnit.MILLISECONDS)
        }

        Thread.sleep(200)

        val future = CompletableFuture.runAsync {
            Truth.assertThat(take(messageQueue, 1)).isEqualTo(1)
        }.orTimeout(1000, TimeUnit.MILLISECONDS)

        Thread.sleep(200)

        messageQueue.sendMessage(1)

        Thread.sleep(200)

        assertions(messageQueue, listOf(future))
    }

    private fun waitFor(futures: List<CompletableFuture<Void>>) {
        CompletableFuture.allOf(*futures.toTypedArray()).get()
    }

    private fun expectTimeout(futures: List<CompletableFuture<Void>>) {
        try {
            CompletableFuture.allOf(*futures.toTypedArray()).get()
            Truth.assertWithMessage("Expected ExecutionException").fail()
        } catch (ignore: ExecutionException) {
            Truth.assertThat(ignore.cause).isInstanceOf(TimeoutException::class.java)
        }
    }
}

private class TestMessageQueue : MessageQueue<Long>() {

    private val readQueue = LinkedBlockingDeque<Long>()

    val readCount = AtomicInteger(0)

    fun sendMessage(message: Long) {
        readQueue.add(message)
    }

    fun takeUnsafe1(type: Long): Long {
        while (true) {
            poll(type)?.let { return it }
            read()
        }
    }

    fun takeUnsafe2(type: Long): Long {
        while (true) {
            synchronized(this) {
                poll(type)?.let { return it }
                read()
            }
        }
    }

    override fun readMessage(): Long {
        readCount.incrementAndGet()
        return readQueue.take()
    }

    override fun getType(v: Long) = v
}
