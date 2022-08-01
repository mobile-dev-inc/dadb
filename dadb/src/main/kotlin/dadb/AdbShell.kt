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

import dadb.AdbShellPacket.Exit
import dadb.AdbShellPacket.StdError
import dadb.AdbShellPacket.StdOut
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
            when (val packet = read()) {
                is Exit -> {
                    val exitCode = packet.payload[0].toInt()
                    return AdbShellResponse(output.toString(), errorOutput.toString(), exitCode)
                }

                is StdOut -> {
                    output.append(String(packet.payload))
                }

                is StdError -> {
                    errorOutput.append(String(packet.payload))
                }
            }
        }
    }

    @Throws(IOException::class)
    fun read(): AdbShellPacket {
        stream.source.apply {
            val id = checkId(readByte().toInt())
            val length = checkLength(id, readIntLe())
            val payload = readByteArray(length.toLong())
            return when (id) {
                ID_STDOUT -> StdOut(payload)
                ID_STDERR -> StdError(payload)
                ID_EXIT -> Exit(payload)
                else -> throw IllegalArgumentException("Invalid shell packet id: $id")
            }
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

sealed class AdbShellPacket(
        open val payload: ByteArray
) {
    abstract val id: Int
    class StdOut(override val payload: ByteArray) : AdbShellPacket(payload) {
        override val id: Int = ID_STDOUT
        override fun toString() = "STDOUT: ${String(payload)}"
    }

    class StdError(override val payload: ByteArray) : AdbShellPacket(payload) {
        override val id: Int = ID_STDERR
        override fun toString() = "STDERR: ${String(payload)}"
    }

    class Exit(override val payload: ByteArray) : AdbShellPacket(payload) {
        override val id: Int = ID_EXIT
        override fun toString() = "EXIT: ${payload[0]}"
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
