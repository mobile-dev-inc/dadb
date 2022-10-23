package dadb.adbserver

import dadb.AdbStream
import dadb.Dadb
import okio.buffer
import okio.sink
import okio.source
import java.io.*
import java.net.Socket
import java.nio.charset.StandardCharsets


object AdbServer {

    /**
     * Experimental API
     *
     * Possible deviceQuery values:
     *
     * host:transport:<serial-number>
     *     Ask to switch the connection to the device/emulator identified by
     *     <serial-number>. After the OKAY response, every client request will
     *     be sent directly to the adbd daemon running on the device.
     *     (Used to implement the -s option)
     *
     * host:transport-usb
     *     Ask to switch the connection to one device connected through USB
     *     to the host machine. This will fail if there are more than one such
     *     devices. (Used to implement the -d convenience option)
     *
     * host:transport-local
     *     Ask to switch the connection to one emulator connected through TCP.
     *     This will fail if there is more than one such emulator instance
     *     running. (Used to implement the -e convenience option)
     *
     * host:transport-any
     *     Another host:transport variant. Ask to switch the connection to
     *     either the device or emulator connect to/running on the host.
     *     Will fail if there is more than one such device/emulator available.
     *     (Used when neither -s, -d or -e are provided)
     */
    @JvmStatic
    @JvmOverloads
    fun createDadb(
        adbServerHost: String = "localhost",
        adbServerPort: Int = 5037,
        deviceQuery: String = "host:transport-any"
    ): Dadb {
        val name = deviceQuery
            .removePrefix("host:") // Use the device query without the host: prefix
            .removePrefix("transport:") // If it's a serial-number, just show that
        return AdbServerDadb(adbServerHost, adbServerPort, deviceQuery, name)
    }

    /**
     * Returns a list of serial numbers of connected devices.
     */
    @JvmStatic
    @JvmOverloads
    fun listDadbs(
        adbServerHost: String = "localhost",
        adbServerPort: Int = 5037,
    ): List<Dadb> {
        if (!AdbBinary.tryStartServer(adbServerHost, adbServerPort)) {
            return emptyList()
        }
        val output = Socket(adbServerHost, adbServerPort).use { socket ->
            send(socket, "host:devices")
            readString(DataInputStream(socket.getInputStream()))
        }
        return output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull {
                val parts = it.split("\t")
                if (parts.size != 2) {
                    null
                } else {
                    parts[0]
                }
            }
            .map { createDadb(adbServerHost, adbServerPort, "host:transport:${it}") }
    }

    internal fun readString(inputStream: DataInputStream): String {
        val encodedLength = readString(inputStream, 4)
        val length = encodedLength.toInt(16)
        return readString(inputStream, length)
    }

    internal fun send(socket: Socket, command: String) {
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

    private fun readString(inputStream: DataInputStream, length: Int): String {
        val responseBuffer = ByteArray(length)
        inputStream.readFully(responseBuffer)
        return String(responseBuffer, StandardCharsets.UTF_8)
    }
}

private class AdbServerDadb constructor(
    private val host: String,
    private val port: Int,
    private val deviceQuery: String,
    private val name: String,
) : Dadb {

    private val supportedFeatures: Set<String>

    init {
        supportedFeatures = open("host:features").use {
            val features = AdbServer.readString(DataInputStream(it.source.inputStream()))
            features.split(",").toSet()
        }
    }

    override fun open(destination: String): AdbStream {
        AdbBinary.ensureServerRunning(host, port)
        val socket = Socket(host, port)
        AdbServer.send(socket, deviceQuery)
        AdbServer.send(socket, destination)
        return object : AdbStream {

            override val source = socket.source().buffer()

            override val sink = socket.sink().buffer()

            override fun close() = socket.close()
        }
    }

    override fun supportsFeature(feature: String): Boolean {
        return feature in supportedFeatures
    }

    override fun close() {}

    override fun toString(): String {
        return name
    }
}
