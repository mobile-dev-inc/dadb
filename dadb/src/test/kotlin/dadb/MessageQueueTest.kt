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

import com.google.common.truth.Truth
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.BeforeTest

internal class MessageQueueTest : BaseConcurrencyTest() {

    private val messageQueue = TestMessageQueue()

    @BeforeTest
    fun setUp() {
        messageQueue.startListening(0)
        messageQueue.startListening(1)
    }

    @Test
    fun basic() {
        send(0, 0)
        take(0, 0)
    }

    @Test
    fun multipleTypes() {
        send(0, 0)
        send(1, 0)

        take(0, 0)
        take(1, 0)
    }

    @Test
    fun concurrency() {
        val sendsRemaining = AtomicInteger(1000)
        launch(20) {
            while (true) {
                val remaining = sendsRemaining.decrementAndGet()
                if (remaining < 0) break
                val localId = remaining % 2
                send(localId, 0)
            }
        }

        val takesRemaining = AtomicInteger(1000)
        launch(20) {
            while (true) {
                val remaining = takesRemaining.decrementAndGet()
                if (remaining < 0) break
                val localId = remaining % 2
                take(localId, 0)
            }
        }

        waitForAll()
    }

    @Test
    fun concurrency_waiting() {
        val takesRemaining = AtomicInteger(1000)
        launch(20) {
            while (true) {
                takesRemaining.decrementAndGet()
                take(0, 0)
            }
        }

        send(1, 0)

        launch(1) {
            take(1, 0)
        }[0].waitFor()
    }

    @Test
    fun concurrency_ordering() {
        (0 until 1000).forEach { send(0, it) }

        launch(20) {
            while (true) {
                take(1, 0)
            }
        }

        launch(1) {
            (0 until 1000).forEach {
                take(0, it)
            }
        }[0].waitFor()
    }

    private fun send(localId: Int, payload: Int) {
        messageQueue.sendMessage(localId, payload)
    }

    private fun take(localId: Int, payload: Int) {
        val message = messageQueue.take(localId, 0)
        Truth.assertThat(message.first).isEqualTo(localId)
        Truth.assertThat(message.second).isEqualTo(payload)
    }
}

private class TestMessageQueue : MessageQueue<Pair<Int, Int>>() {

    private val readQueue = LinkedBlockingDeque<Pair<Int, Int>>()

    val readCount = AtomicInteger(0)

    fun sendMessage(localId: Int, payload: Int) {
        readQueue.add(localId to payload)
    }

    override fun readMessage(): Pair<Int, Int> {
        readCount.incrementAndGet()
        return readQueue.take()
    }

    override fun getLocalId(message: Pair<Int, Int>) = message.first

    override fun getCommand(message: Pair<Int, Int>) = 0

    override fun isCloseCommand(message: Pair<Int, Int>) = false
}
