package dadb.core

import okio.Sink
import okio.buffer

internal class AdbWriter(sink: Sink) : AutoCloseable {

    private val bufferedSink = sink.buffer()

    fun write(
            command: Int,
            arg0: Int,
            arg1: Int,
            payload: ByteArray?,
            offset: Int,
            length: Int
    ) {
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