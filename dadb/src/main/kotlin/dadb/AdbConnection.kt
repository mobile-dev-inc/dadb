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
import org.jetbrains.annotations.TestOnly
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Socket
import kotlin.random.Random

internal class AdbConnection internal constructor(
        adbReader: AdbReader,
        private val adbWriter: AdbWriter,
        private val closeable: Closeable?,
        private val connectionString: String,
        private val version: Int,
        private val maxPayloadSize: Int
) : Dadb {

    private val messageQueue = AdbMessageQueue(adbReader)

    @Throws(IOException::class)
    override fun shell(command: String): AdbShellResponse {
        openShell(command).use { stream ->
            return stream.readAll()
        }
    }

    @Throws(IOException::class)
    override fun openShell(command: String): AdbShellStream {
        val stream = open("shell,v2,raw:$command")
        return AdbShellStream(stream)
    }

    @Throws(IOException::class)
    override fun install(file: File) {
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
    override fun uninstall(packageName: String) {
        val response = shell("cmd package uninstall $packageName")
        if (response.exitCode != 0) {
            throw IOException("Uninstall failed: ${response.allOutput}")
        }
    }

    @Throws(IOException::class)
    override fun abbExec(vararg command: String): AdbStream {
        val destination = "abb_exec:${command.joinToString("\u0000")}"
        return open(destination)
    }

    @Throws(IOException::class)
    override fun root() {
        val response = restartAdb("root:")
        if (!response.startsWith("restarting") && !response.contains("already")) {
            throw IOException("Failed to restart adb as root: $response")
        }
        waitRootOrClose(root = true)
    }

    @Throws(IOException::class)
    override fun unroot() {
        val response = restartAdb("unroot:")
        if (!response.startsWith("restarting") && !response.contains("not running as root")) {
            throw IOException("Failed to restart adb as root: $response")
        }
        waitRootOrClose(root = false)
    }

    @Throws(IOException::class)
    override fun open(destination: String): AdbStream {
        val localId = newId()
        messageQueue.startListening(localId)
        try {
            adbWriter.writeOpen(localId, destination)
            val message = messageQueue.take(localId, Constants.CMD_OKAY)
            val remoteId = message.arg0
            return AdbStream(messageQueue, adbWriter, maxPayloadSize, localId, remoteId)
        } catch (e: Throwable) {
            messageQueue.stopListening(localId)
            throw e
        }
    }

    private fun waitRootOrClose(root: Boolean) {
        while (true) {
            try {
                val response = shell("getprop service.adb.root")
                val propValue = if (root) 1 else 0
                if (response.output == "$propValue\n") return
            } catch (e: IOException) {
                return
            }
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

    private fun newId(): Int {
        return Random.nextInt()
    }

    @TestOnly
    internal fun ensureEmpty() {
        messageQueue.ensureEmpty()
    }

    override fun close() {
        try {
            messageQueue.close()
            adbWriter.close()
            closeable?.close()
        } catch (ignore: Throwable) {}
    }

    companion object {

        fun connect(socket: Socket, keyPair: AdbKeyPair? = null): AdbConnection {
            val source = socket.source()
            val sink = socket.sink()
            return connect(source, sink, keyPair, socket)
        }

        private fun connect(source: Source, sink: Sink, keyPair: AdbKeyPair? = null, closeable: Closeable? = null): AdbConnection {
            val adbReader = AdbReader(source)
            val adbWriter = AdbWriter(sink)

            try {
                return connect(adbReader, adbWriter, keyPair, closeable)
            } catch (t: Throwable) {
                adbReader.close()
                adbWriter.close()
                throw t
            }
        }

        private fun connect(adbReader: AdbReader, adbWriter: AdbWriter, keyPair: AdbKeyPair?, closeable: Closeable?): AdbConnection {
            adbWriter.writeConnect()

            var message = adbReader.readMessage()

            if (message.command == Constants.CMD_AUTH) {
                checkNotNull(keyPair) { "Authentication required but no KeyPair provided" }
                check(message.arg0 == Constants.AUTH_TYPE_TOKEN) { "Unsupported auth type: $message" }

                val signature = keyPair.signPayload(message)
                adbWriter.writeAuth(Constants.AUTH_TYPE_SIGNATURE, signature)

                message = adbReader.readMessage()
                if (message.command == Constants.CMD_AUTH) {
                    adbWriter.writeAuth(Constants.AUTH_TYPE_RSA_PUBLIC, keyPair.publicKeyBytes)
                    message = adbReader.readMessage()
                }
            }

            if (message.command != Constants.CMD_CNXN) throw IOException("Connection failed: $message")

            val connectionString = String(message.payload)
            val version = message.arg0
            val maxPayloadSize = message.arg1

            return AdbConnection(adbReader, adbWriter, closeable, connectionString, version, maxPayloadSize)
        }
    }
}