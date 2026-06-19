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
import okio.Buffer
import okio.Source
import okio.Timeout
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class AdbStreamTest {

    @Test
    fun testLargeRemoteWrite() {
        val payload = ByteArray(1024 * 1024).apply { fill(1) }
        val adbReader = createAdbReader(1, 2, payload)
        val messageQueue = AdbMessageQueue(adbReader)
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(messageQueue, AdbWriter(Buffer()), 1024, 1, 2)
        Truth.assertThat(stream.source.readByteArray()).isEqualTo(payload)
    }

    @Test
    fun peerCloseIsCleanEof() {
        // Reader yields a CLSE for localId=1 and nothing else.
        val buffer = Buffer()
        AdbWriter(buffer).write(Constants.CMD_CLSE, 2, 1, null, 0, 0)
        val messageQueue = AdbMessageQueue(AdbReader(buffer))
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(messageQueue, AdbWriter(Buffer()), 1024, 1, 2)

        // A clean peer close reads as EOF (empty), NOT an exception.
        Truth.assertThat(stream.source.readByteArray()).isEqualTo(ByteArray(0))
    }

    @Test
    fun socketFaultThrowsConnectionClosed() {
        // Empty reader: the next read hits EOF on the socket itself (not a protocol CLSE).
        val messageQueue = AdbMessageQueue(AdbReader(Buffer()))
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(messageQueue, AdbWriter(Buffer()), 1024, 1, 2)

        assertFailsWith<AdbConnectionClosedException> { stream.source.readByteArray() }
    }

    @Test
    fun readTimeoutThrowsAdbTimeout() {
        // Reader whose socket read trips SO_TIMEOUT instead of returning data or EOF: a stall, which
        // surfaces as AdbTimeoutException rather than AdbConnectionClosedException.
        val timingOutReader = AdbReader(object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw SocketTimeoutException("Read timed out")
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() {}
        })
        val messageQueue = AdbMessageQueue(timingOutReader)
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(messageQueue, AdbWriter(Buffer()), 1024, 1, 2)

        assertFailsWith<AdbTimeoutException> { stream.source.readByteArray() }
    }

    private fun createAdbReader(localId: Int, remoteId: Int, writePayload: ByteArray): AdbReader {
        val source = Buffer()
        val writer = AdbWriter(source)
        writer.write(Constants.CMD_WRTE, remoteId, localId, writePayload, 0, writePayload.size)
        // Append a proper A_CLSE so the stream ends cleanly, matching real device behaviour.
        writer.write(Constants.CMD_CLSE, remoteId, localId, null, 0, 0)
        return AdbReader(source)
    }
}
