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
import okio.Source
import okio.sink
import okio.source
import org.jetbrains.annotations.TestOnly
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.util.*

internal class AdbConnection internal constructor(
        adbReader: AdbReader,
        private val adbWriter: AdbWriter,
        private val closeable: Closeable?,
        private val supportedFeatures: Set<String>,
        private val version: Int,
        private val maxPayloadSize: Int
) : AutoCloseable {

    private val random = Random()
    private val messageQueue = AdbMessageQueue(adbReader)

    @Throws(IOException::class)
    fun open(destination: String): AdbStream {
        val localId = newId()
        messageQueue.startListening(localId)
        try {
            adbWriter.writeOpen(localId, destination)
            val message = messageQueue.take(localId, Constants.CMD_OKAY)
            val remoteId = message.arg0
            return AdbStreamImpl(messageQueue, adbWriter, maxPayloadSize, localId, remoteId)
        } catch (e: Throwable) {
            messageQueue.stopListening(localId)
            throw e
        }
    }

    fun supportsFeature(feature: String): Boolean {
        return supportedFeatures.contains(feature)
    }

    private fun newId(): Int {
        return random.nextInt()
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

            val connectionString = parseConnectionString(String(message.payload))
            val version = message.arg0
            val maxPayloadSize = message.arg1

            return AdbConnection(adbReader, adbWriter, closeable, connectionString.features, version, maxPayloadSize)
        }

        // ie: "device::ro.product.name=sdk_gphone_x86;ro.product.model=Android SDK built for x86;ro.product.device=generic_x86;features=fixed_push_symlink_timestamp,apex,fixed_push_mkdir,stat_v2,abb_exec,cmd,abb,shell_v2"
        private fun parseConnectionString(connectionString: String): ConnectionString {
            val keyValues = connectionString.substringAfter("device::")
                    .split(";")
                    .map { it.split("=") }
                    .mapNotNull { if (it.size != 2) null else it[0] to it[1] }
                    .toMap()
            if ("features" !in keyValues) throw IOException("Failed to parse features from connection string: $connectionString")
            val features = keyValues.getValue("features").split(",").toSet()
            return ConnectionString(features)
        }
    }
}

private data class ConnectionString(val features: Set<String>)