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

import okio.Buffer
import okio.BufferedSource
import okio.source
import org.jetbrains.annotations.TestOnly
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.random.Random

class AdbConnection internal constructor(
        adbReader: AdbReader,
        private val adbWriter: AdbWriter,
        private val closeable: Closeable?,
        private val connectionString: String,
        private val version: Int,
        private val maxPayloadSize: Int
) : AutoCloseable {

    private val messageQueue = AdbMessageQueue(adbReader)

    @Throws(IOException::class)
    fun shell(command: String = ""): AdbShellResponse {
        openShell(command).use { stream ->
            return stream.readAll()
        }
    }

    @Throws(IOException::class)
    fun openShell(command: String = ""): AdbShellStream {
        val stream = open("shell,v2,raw:$command")
        return AdbShellStream(stream)
    }

    @Throws(IOException::class)
    fun install(file: File) {
        abbExec("package", "install", "-S", file.length().toString()).use { stream ->
            stream.sink.writeAll(file.source())
            stream.sink.flush()
            val response = stream.source.readString(Charsets.UTF_8)
            if (!response.startsWith("Success")) {
                throw IOException("Install failed: $response")
            }
        }
    }

    @Throws(IOException::class)
    fun uninstall(packageName: String) {
        val response = shell("cmd package uninstall $packageName")
        if (response.exitCode != 0) {
            throw IOException("Uninstall failed: ${response.allOutput}")
        }
    }

    @Throws(IOException::class)
    fun abbExec(vararg command: String): AdbStream {
        val destination = "abb_exec:${command.joinToString("\u0000")}"
        return open(destination)
    }

    @Throws(IOException::class)
    fun root() {
        val response = restartAdb("root:")
        if (!response.startsWith("restarting") && !response.contains("already")) {
            throw IOException("Failed to restart adb as root: $response")
        }
    }

    @Throws(IOException::class)
    fun unroot() {
        val response = restartAdb("unroot:")
        if (!response.startsWith("restarting") && !response.contains("not running as root")) {
            throw IOException("Failed to restart adb as root: $response")
        }
    }

    private fun restartAdb(destination: String): String {
        open(destination).use { stream ->
            return stream.source.readUntil('\n'.toByte()).readString(Charsets.UTF_8)
        }
    }

    private fun BufferedSource.readUntil(endByte: Byte): Buffer {
        val buffer = Buffer()
        while (true) {
            val b = readByte()
            buffer.writeByte(b.toInt())
            if (b == endByte) return buffer
        }
    }

    @Throws(IOException::class)
    fun open(destination: String): AdbStream {
        val localId = newId()
        messageQueue.startListening(localId)
        adbWriter.writeOpen(localId, destination)
        val message = messageQueue.take(localId, Constants.CMD_OKAY)
        val remoteId = message.arg0
        return AdbStream(messageQueue, adbWriter, maxPayloadSize, localId, remoteId)
    }

    private fun newId(): Int {
        return Random.nextInt()
    }

    override fun close() {
        try {
            messageQueue.close()
            adbWriter.close()
            closeable?.close()
        } catch (ignore: Throwable) {}
    }

    @TestOnly
    internal fun ensureEmpty() {
        messageQueue.ensureEmpty()
    }
}