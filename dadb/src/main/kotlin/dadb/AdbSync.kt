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
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.jvm.Throws

internal const val LIST = "LIST"
internal const val RECV = "RECV"
internal const val SEND = "SEND"
internal const val STAT = "STAT"
internal const val DATA = "DATA"
internal const val DONE = "DONE"
internal const val OKAY = "OKAY"
internal const val QUIT = "QUIT"
internal const val FAIL = "FAIL"

internal val SYNC_IDS = setOf(LIST, RECV, SEND, STAT, DATA, DONE, OKAY, QUIT, FAIL)

private class Packet(val id: String, val arg: Int)

class AdbSyncStream(
        private val stream: AdbStream
) : AutoCloseable {

    @Throws(IOException::class)
    fun send(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long) {
        val remote = "$remotePath,$mode"
        writePacket(SEND, remote.length)

        stream.sink.apply {
            writeString(remote, StandardCharsets.UTF_8)
            flush()
        }

        val sink = object : Sink {

            override fun write(source: Buffer, byteCount: Long) {
                writePacket(DATA, byteCount.toInt())
                stream.sink.write(source, byteCount)
            }

            override fun close() = Unit

            override fun flush() = Unit

            override fun timeout() = Timeout.NONE
        }

        // Use java.io since we can't change the internal Okio segment size
        val maxDataPacketSize = 64 * 1024
        val bufferedInput = source.buffer().inputStream()
        val bufferedOutput = sink.buffer().outputStream().buffered(maxDataPacketSize)
        bufferedInput.copyTo(bufferedOutput)
        bufferedOutput.flush()

        writePacket(DONE, (lastModifiedMs / 1000).toInt())

        stream.sink.flush()

        val packet = readPacket()
        if (packet.id == FAIL) {
            val message = stream.source.readString(packet.arg.toLong(), StandardCharsets.UTF_8)
            throw IOException("Sync failed: $message")
        }
        if (packet.id != OKAY) throw IOException("Unexpected sync packet id: ${packet.id}")
    }

    @Throws(IOException::class)
    fun recv(sink: Sink, remotePath: String) {
        writePacket(RECV, remotePath.length)
        stream.sink.apply {
            writeString(remotePath, StandardCharsets.UTF_8)
            flush()
        }

        val source = object : Source {

            override fun read(sink: Buffer, byteCount: Long): Long {
                val packet = readPacket()
                if (packet.id == DONE) return -1
                if (packet.id == FAIL) {
                    val message = stream.source.readString(packet.arg.toLong(), StandardCharsets.UTF_8)
                    throw IOException("Sync failed: $message")
                }
                if (packet.id != DATA) throw IOException("Unexpected sync packet id: ${packet.id}")
                val chunkSize = packet.arg
                stream.source.readFully(sink, chunkSize.toLong())
                return chunkSize.toLong()
            }

            override fun timeout() = Timeout.NONE

            override fun close() = Unit
        }

        source.buffer().readAll(sink)

        sink.flush()
    }

    private fun writePacket(id: String, arg: Int) {
        stream.sink.apply {
            writeString(id, StandardCharsets.UTF_8)
            writeIntLe(arg)
            flush()
        }
    }

    private fun readPacket(): Packet {
        val id = stream.source.readString(4, StandardCharsets.UTF_8)
        val arg = stream.source.readIntLe()
        return Packet(id, arg)
    }

    override fun close() {
        writePacket(QUIT, 0)
        stream.close()
    }
}