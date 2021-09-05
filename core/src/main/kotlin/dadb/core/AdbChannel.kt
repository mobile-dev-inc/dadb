package dadb.core

import okio.Sink
import okio.Source
import java.io.IOException
import java.security.KeyPair

class AdbChannel private constructor(
        private val adbReader: AdbReader,
        private val adbWriter: AdbWriter,
        private val connectionString: String,
        private val version: Int,
        private val maxPayloadSize: Int
) {

    companion object {

        fun connect(source: Source, sink: Sink, keyPair: KeyPair? = null) {
            val adbReader = AdbReader(source)
            val adbWriter = AdbWriter(sink)

            try {
                connect(adbReader, adbWriter, keyPair)
            } catch (t: Throwable) {
                adbReader.close()
                adbWriter.close()
                throw t
            }
        }

        private fun connect(adbReader: AdbReader, adbWriter: AdbWriter, keyPair: KeyPair?): AdbChannel {
            adbWriter.writeConnect()

            var message = adbReader.readMessage()

            if (message.command == Constants.CMD_AUTH) {
                if (keyPair == null) throw IllegalStateException("Authentication required but no KeyPair provided")
                if (message.arg0 != Constants.AUTH_TYPE_TOKEN) throw IllegalStateException("Unsupported auth type: $message")

                val signature = AuthUtils.signPayload(keyPair.private, message)
                adbWriter.writeAuth(signature)

                message = adbReader.readMessage()
                if (message.command == Constants.CMD_AUTH) {
                    throw IllegalStateException("Public key authentication unimplemented")
                }
            }

            if (message.command != Constants.CMD_CNXN) throw IOException("Connection failed: $message")

            val connectionString = String(message.payload)
            val version = message.arg0
            val maxPayloadSize = message.arg1

            return AdbChannel(adbReader, adbWriter, connectionString, version, maxPayloadSize)
        }
    }
}