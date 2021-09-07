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

@file:JvmName("AdbShell")

package dadb

import java.io.IOException

const val ID_STDIN = 0
const val ID_STDOUT = 1
const val ID_STDERR = 2
const val ID_EXIT = 3
const val ID_CLOSE_STDIN = 3

class AdbShellStream(
        private val stream: AdbStream
) : AutoCloseable {

    @Throws(IOException::class)
    fun readAll(): AdbShellResponse {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        while (true) {
            val packet = read()
            val id = packet.id
            if (id == ID_EXIT) {
                val exitCode = packet.payload[0].toInt()
                return AdbShellResponse(output.toString(), errorOutput.toString(), exitCode)
            } else if (id == ID_STDOUT || id == ID_STDERR) {
                val sb = if (id == ID_STDOUT) output else errorOutput
                sb.append(String(packet.payload))
            } else {
                throw IllegalStateException("Invalid shell packet id: $id")
            }
        }
    }

    @Throws(IOException::class)
    fun read(): AdbShellPacket {
        stream.source.apply {
            val id = checkId(readByte().toInt())
            val length = checkLength(id, readIntLe())
            val payload = readByteArray(length.toLong())
            return AdbShellPacket(id, payload)
        }
    }

    @Throws(IOException::class)
    fun write(string: String) {
        write(ID_STDIN, string.toByteArray())
    }

    @Throws(IOException::class)
    fun write(id: Int, payload: ByteArray? = null) {
        stream.sink.apply {
            writeByte(id)
            writeIntLe(payload?.size ?: 0)
            if (payload != null) write(payload)
            flush()
        }
    }

    override fun close() {
        stream.close()
    }

    private fun checkId(id: Int): Int {
        check(id == ID_STDOUT || id == ID_STDERR || id == ID_EXIT) {
            "Invalid shell packet id: $id"
        }
        return id
    }

    private fun checkLength(id: Int, length: Int): Int {
        check(length >= 0) { "Shell packet length must be >= 0: $length" }
        check(id != ID_EXIT || length == 1) { "Shell exit packet does not have payload length == 1: $length" }
        return length
    }
}

class AdbShellPacket(
        val id: Int,
        val payload: ByteArray
) {

    override fun toString() = "${idStr(id)}: ${payloadStr(id, payload)}"

    companion object {

        private fun idStr(id: Int) = when(id) {
            ID_STDOUT -> "STDOUT"
            ID_STDERR -> "STDERR"
            ID_EXIT -> "EXIT"
            else -> throw IllegalArgumentException("Invalid shell packet id: $id")
        }

        private fun payloadStr(id: Int, payload: ByteArray) = if (id == ID_EXIT) {
            "${payload[0]}"
        } else {
            String(payload)
        }
    }
}

class AdbShellResponse(
        val output: String,
        val errorOutput: String,
        val exitCode: Int
) {

    val allOutput: String by lazy { "$output$errorOutput" }

    override fun toString() = "Shell response ($exitCode):\n$allOutput"
}
