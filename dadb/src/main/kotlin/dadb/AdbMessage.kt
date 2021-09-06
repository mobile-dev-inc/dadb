package dadb

internal class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payloadLength: Int,
        val checksum: Int,
        val magic: Int,
        val payload: ByteArray
) {

    override fun toString() = "${commandStr(command)}[${argStr(arg0)}, ${argStr(arg1)}] ${String(payload)}"

    companion object {

        private fun argStr(arg: Int) = String.format("%X", arg)

        private fun commandStr(command: Int) = when (command) {
            Constants.CMD_AUTH -> "AUTH";
            Constants.CMD_CNXN -> "CNXN";
            Constants.CMD_OPEN -> "OPEN";
            Constants.CMD_OKAY -> "OKAY";
            Constants.CMD_CLSE -> "CLSE";
            Constants.CMD_WRTE -> "WRTE";
            else -> "????"
        }
    }
}
