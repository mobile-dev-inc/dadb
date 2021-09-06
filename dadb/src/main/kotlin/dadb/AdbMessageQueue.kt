package dadb

internal class AdbMessageQueue(private val adbReader: AdbReader) : AutoCloseable, MessageQueue<AdbMessage>() {

    override fun readMessage() = adbReader.readMessage()

    override fun getLocalId(message: AdbMessage) = message.arg1

    override fun getCommand(message: AdbMessage) = message.command

    override fun close() {
        adbReader.close()
    }

    override fun isCloseCommand(message: AdbMessage) = message.command == Constants.CMD_CLSE
}
