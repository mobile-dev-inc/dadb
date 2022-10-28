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

import okio.Sink
import okio.buffer
import java.nio.ByteBuffer

internal class AdbWriter(sink: Sink) : AutoCloseable {

    private val bufferedSink = sink.buffer()

    fun writeConnect() = write(
            Constants.CMD_CNXN,
            Constants.CONNECT_VERSION,
            Constants.CONNECT_MAXDATA,
            Constants.CONNECT_PAYLOAD,
            0,
            Constants.CONNECT_PAYLOAD.size
    )

    fun writeAuth(authType: Int, authPayload: ByteArray) = write(
            Constants.CMD_AUTH,
            authType,
            0,
            authPayload,
            0,
            authPayload.size
    )

    fun writeOpen(localId: Int, destination: String) {
        val destinationBytes = destination.toByteArray()
        val buffer = ByteBuffer.allocate(destinationBytes.size + 1)
        buffer.put(destinationBytes)
        buffer.put(0)
        val payload = buffer.array()
        write(Constants.CMD_OPEN, localId, 0, payload, 0, payload.size)
    }

    fun writeWrite(localId: Int, remoteId: Int, payload: ByteArray, offset: Int, length: Int) {
        write(Constants.CMD_WRTE, localId, remoteId, payload, offset, length)
    }

    fun writeClose(localId: Int, remoteId: Int) {
        write(Constants.CMD_CLSE, localId, remoteId, null, 0, 0)
    }

    fun writeOkay(localId: Int, remoteId: Int) {
        write(Constants.CMD_OKAY, localId, remoteId, null, 0, 0)
    }

    fun write(
            command: Int,
            arg0: Int,
            arg1: Int,
            payload: ByteArray?,
            offset: Int,
            length: Int
    ) {
        log { "(${Thread.currentThread().name}) > ${AdbMessage(command, arg0, arg1, length, 0, 0, payload ?: ByteArray(0))}" }
        synchronized(bufferedSink) {
            bufferedSink.apply {
                writeIntLe(command)
                writeIntLe(arg0)
                writeIntLe(arg1)
                if (payload == null) {
                    writeIntLe(0)
                    writeIntLe(0)
                } else {
                    writeIntLe(length)
                    writeIntLe(payloadChecksum(payload))
                }
                writeIntLe(command xor -0x1)
                if (payload != null) {
                    write(payload, offset, length)
                }
                flush()
            }
        }
    }

    override fun close() {
        bufferedSink.close()
    }

    companion object {

        private fun payloadChecksum(payload: ByteArray): Int {
            var checksum = 0
            for (byte in payload) {
                checksum += byte.toUByte().toInt()
            }
            return checksum
        }
    }
}