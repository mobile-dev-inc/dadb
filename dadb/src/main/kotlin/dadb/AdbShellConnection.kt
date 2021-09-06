package dadb

class AdbShellConnection(
        private val connection: AdbConnection
) {

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
        connection.source.apply {
            val id = checkId(readByte().toInt())
            val length = checkLength(id, readIntLe())
            val payload = readByteArray(length.toLong())
            return AdbShellPacket(id, payload)
        }
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
)

class AdbShellResponse(
        val output: String,
        val errorOutput: String,
        val exitCode: Int
) {
    val allOutput: String by lazy { "$output$errorOutput" }
}
