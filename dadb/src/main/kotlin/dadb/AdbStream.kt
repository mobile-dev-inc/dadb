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

import okio.*
import java.lang.Integer.min
import java.nio.ByteBuffer

interface AdbStream : AutoCloseable {

    val source: BufferedSource

    val sink: BufferedSink
}

internal class AdbStreamImpl internal constructor(
        private val messageQueue: AdbMessageQueue,
        private val adbWriter: AdbWriter,
        private val maxPayloadSize: Int,
        private val localId: Int,
        private val remoteId: Int
) : AdbStream {

    private var isClosed = false

    override val source = object : Source {

        private var message: AdbMessage? = null
        private var bytesRead = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            val message = message() ?: return -1

            val bytesRemaining = message.payloadLength - bytesRead
            val bytesToRead = Math.min(byteCount.toInt(), bytesRemaining)

            sink.write(message.payload, bytesRead, bytesToRead)

            bytesRead += bytesToRead

            check(bytesRead <= message.payloadLength)

            if (bytesRead == message.payloadLength) {
                this.message = null
                adbWriter.writeOkay(localId, remoteId)
            }

            return bytesToRead.toLong()
        }

        private fun message(): AdbMessage? {
            message?.let { return it }
            val nextMessage = nextMessage(Constants.CMD_WRTE)
            message = nextMessage
            bytesRead = 0
            return nextMessage
        }

        override fun close() {}

        override fun timeout() = Timeout.NONE
    }.buffer()

    override val sink = object : Sink {

        private val buffer = ByteBuffer.allocate(maxPayloadSize)

        override fun write(source: Buffer, byteCount: Long) {
            var remainingBytes = byteCount
            while (true) {
                remainingBytes -= writeToBuffer(source, byteCount)
                if (remainingBytes == 0L) return
                check(remainingBytes > 0L)
            }
        }

        private fun writeToBuffer(source: BufferedSource, byteCount: Long): Int {
            val bytesToWrite = min(buffer.remaining(), byteCount.toInt())
            val bytesWritten = source.read(buffer.array(), buffer.position(), bytesToWrite)

            buffer.position(buffer.position() + bytesWritten)
            if (buffer.remaining() == 0) flush()

            return bytesWritten
        }

        override fun flush() {
            adbWriter.writeWrite(localId, remoteId, buffer.array(), 0, buffer.position())
            buffer.clear()
        }

        override fun close() {}

        override fun timeout() = Timeout.NONE
    }.buffer()

    private fun nextMessage(command: Int): AdbMessage? {
        return try {
            messageQueue.take(localId, command)
        } catch (e: IOException) {
            close()
            return null
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true

        adbWriter.writeClose(localId, remoteId)

        messageQueue.stopListening(localId)
    }
}
