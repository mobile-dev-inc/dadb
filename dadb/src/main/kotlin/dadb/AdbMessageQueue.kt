package dadb

internal class AdbMessageQueue(private val adbReader: AdbReader) : MessageQueue<AdbMessage>() {

    override fun readMessage() = adbReader.readMessage()

    override fun getLocalId(message: AdbMessage) = message.arg1

    override fun getCommand(message: AdbMessage) = message.command
}
