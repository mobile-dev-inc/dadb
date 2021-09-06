package dadb

import okio.Sink
import okio.Source
import okio.sink
import okio.source
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import kotlin.random.Random

class AdbChannel private constructor(
        private val adbReader: AdbReader,
        private val adbWriter: AdbWriter,
        private val closeable: Closeable?,
        private val connectionString: String,
        private val version: Int,
        private val maxPayloadSize: Int
) : AutoCloseable {

    private val messageQueue = AdbMessageQueue(adbReader)

    fun connect() {
        val localId = newId()
        messageQueue.startListening(localId)
    }

    private fun newId(): Int {
        return Random.nextInt()
    }

    override fun close() {
        try {
            adbReader.close()
            adbWriter.close()
            closeable?.close()
        } catch (ignore: Throwable) {}
    }

    companion object {

        fun open(socket: Socket, keyPair: AdbKeyPair? = null): AdbChannel {
            val source = socket.source()
            val sink = socket.sink()
            return open(source, sink, keyPair, socket)
        }

        private fun open(source: Source, sink: Sink, keyPair: AdbKeyPair? = null, closeable: Closeable? = null): AdbChannel {
            val adbReader = AdbReader(source)
            val adbWriter = AdbWriter(sink)

            try {
                return open(adbReader, adbWriter, keyPair, closeable)
            } catch (t: Throwable) {
                adbReader.close()
                adbWriter.close()
                throw t
            }
        }

        private fun open(adbReader: AdbReader, adbWriter: AdbWriter, keyPair: AdbKeyPair?, closeable: Closeable?): AdbChannel {
            adbWriter.writeConnect()

            var message = adbReader.readMessage()

            if (message.command == Constants.CMD_AUTH) {
                if (keyPair == null) throw IllegalStateException("Authentication required but no KeyPair provided")
                if (message.arg0 != Constants.AUTH_TYPE_TOKEN) throw IllegalStateException("Unsupported auth type: $message")

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

            return AdbChannel(adbReader, adbWriter, closeable, connectionString, version, maxPayloadSize)
        }
    }
}