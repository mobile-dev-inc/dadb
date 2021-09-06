package dadb

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payloadLength: Int,
        val checksum: Int,
        val magic: Int,
        val payload: ByteArray
) {

    override fun toString() = "${commandStr()}[${argStr(arg0)}, ${argStr(arg1)}] ${payloadStr()}"

    private fun payloadStr(): String {
        if (payloadLength == 0) return ""
        return when (command) {
            Constants.CMD_AUTH -> if (arg0 == Constants.AUTH_TYPE_RSA_PUBLIC) String(payload) else "auth[${payloadLength}]"
            Constants.CMD_WRTE -> writePayloadStr()
            Constants.CMD_OPEN -> String(payload, 0, payloadLength - 1)
            else -> "payload[$payloadLength]"
        }
    }

    private fun writePayloadStr(): String {
        return shellV2WritePayloadStr()?.let { "[shell] $it" } ?: "payload[$payloadLength]"
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun shellV2WritePayloadStr(): String? {
        val buffer = ByteBuffer.wrap(payload, 0, payloadLength).order(ByteOrder.LITTLE_ENDIAN)
        val id = buffer.get().toInt()
        if (id < 0 || id > 3) return null
        val length = buffer.getInt()
        if (length != buffer.remaining()) return null
        if (id == Constants.SHELL_ID_EXIT) return "EXIT[${buffer.get()}]"
        return String(payload, 5, payloadLength - 5)
    }

    private fun argStr(arg: Int) = String.format("%X", arg)

    private fun commandStr() = when (command) {
        Constants.CMD_AUTH -> "AUTH";
        Constants.CMD_CNXN -> "CNXN";
        Constants.CMD_OPEN -> "OPEN";
        Constants.CMD_OKAY -> "OKAY";
        Constants.CMD_CLSE -> "CLSE";
        Constants.CMD_WRTE -> "WRTE";
        else -> "????"
    }
}
