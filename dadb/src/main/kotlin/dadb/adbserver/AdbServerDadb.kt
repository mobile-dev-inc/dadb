package dadb.adbserver

import dadb.AdbStream
import dadb.Dadb
import okio.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets


internal class AdbServerDadb(
    private val host: String,
    private val port: Int,
    private val deviceQuery: String,
) : Dadb {

    override fun open(destination: String): AdbStream {
        val socket = Socket(host, port)
        send(socket, deviceQuery)
        send(socket, destination)
        return object : AdbStream {

            override val source = socket.source().buffer()

            override val sink = socket.sink().buffer()

            override fun close() = socket.close()
        }
    }

    override fun supportsFeature(feature: String): Boolean {
        return true
    }

    override fun close() {}

    companion object {

        fun create(host: String, port: Int): AdbServerDadb {
            return AdbServerDadb(host, port, "host:transport-any")
        }

        private fun send(socket: Socket, command: String) {
            val inputStream = DataInputStream(socket.getInputStream())
            val outputStream = DataOutputStream(socket.getOutputStream())

            writeString(outputStream, command)

            val response = readString(inputStream, 4)
            if (response != "OKAY") {
                val error = readString(inputStream)
                throw IOException("Command failed ($command): $error")
            }
        }

        private fun writeString(outputStream: DataOutputStream, string: String) {
            OutputStreamWriter(outputStream, StandardCharsets.UTF_8).apply {
                write(String.format("%04x", string.toByteArray().size))
                write(string)
                flush()
            }
        }

        private fun readString(inputStream: DataInputStream): String {
            val encodedLength = readString(inputStream, 4)
            val length = encodedLength.toInt(16)
            return readString(inputStream, length)
        }

        private fun readString(inputStream: DataInputStream, length: Int): String {
            val responseBuffer = ByteArray(length)
            inputStream.readFully(responseBuffer)
            return String(responseBuffer, StandardCharsets.UTF_8)
        }
    }
}

fun main() {
    val dadb = AdbServerDadb.create("localhost", 5037)
    println(dadb.shell("echo hello"))
}
