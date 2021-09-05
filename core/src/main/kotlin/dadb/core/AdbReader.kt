package dadb.core

import okio.Source
import okio.buffer

internal class AdbReader(source: Source) : AutoCloseable {

    private val bufferedSource = source.buffer()

    fun readMessage(): AdbMessage {
        bufferedSource.apply {
            val command = readIntLe()
            val arg0 = readIntLe()
            val arg1 = readIntLe()
            val payloadLength = readIntLe()
            val checksum = readIntLe()
            val magic = readIntLe()
            val payload = readByteArray(payloadLength.toLong())
            return AdbMessage(command, arg0, arg1, payloadLength, checksum, magic, payload)
        }
    }

    override fun close() {
        bufferedSource.close()
    }
}
