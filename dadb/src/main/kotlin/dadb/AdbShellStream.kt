package dadb

fun AdbConnection.shellV2(command: String = ""): AdbShellStream {
    val stream = open("shell,v2,raw:$command")
    return AdbShellStream(stream)
}

class AdbShellStream(
        private val stream: AdbStream
) : AutoCloseable {

    fun readAll(): AdbShellResponse {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        while (true) {
            val packet = read()
            val id = packet.id
            if (id == Constants.SHELL_ID_EXIT) {
                val exitCode = packet.payload[0].toInt()
                return AdbShellResponse(output.toString(), errorOutput.toString(), exitCode)
            } else if (id == Constants.SHELL_ID_STDOUT || id == Constants.SHELL_ID_STDERR) {
                val sb = if (id == Constants.SHELL_ID_STDOUT) output else errorOutput
                sb.append(String(packet.payload))
            } else {
                throw IllegalStateException("Invalid shell packet id: $id")
            }
        }
    }

    fun read(): AdbShellPacket {
        stream.source.apply {
            val id = checkId(readByte().toInt())
            val length = checkLength(id, readIntLe())
            val payload = readByteArray(length.toLong())
            return AdbShellPacket(id, payload)
        }
    }

    fun write(string: String) {
        write(Constants.SHELL_ID_STDIN, string.toByteArray())
    }

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
        check(id == Constants.SHELL_ID_STDOUT || id == Constants.SHELL_ID_STDERR || id == Constants.SHELL_ID_EXIT) {
            "Invalid shell packet id: $id"
        }
        return id
    }

    private fun checkLength(id: Int, length: Int): Int {
        check(length >= 0) { "Shell packet length must be >= 0: $length" }
        check(id != Constants.SHELL_ID_EXIT || length == 1) { "Shell exit packet does not have payload length == 1: $length" }
        return length
    }
}

class AdbShellPacket(
        val id: Int,
        val payload: ByteArray
) {

    override fun toString() = "${idStr(id)}: ${payloadStr(id, payload)}"

    companion object {

        private fun idStr(id: Int) = when(id) {
            Constants.SHELL_ID_STDOUT -> "STDOUT"
            Constants.SHELL_ID_STDERR -> "STDERR"
            Constants.SHELL_ID_EXIT -> "EXIT"
            else -> throw IllegalArgumentException("Invalid shell packet id: $id")
        }

        private fun payloadStr(id: Int, payload: ByteArray) = if (id == Constants.SHELL_ID_EXIT) {
            "${payload[0]}"
        } else {
            String(payload)
        }
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
