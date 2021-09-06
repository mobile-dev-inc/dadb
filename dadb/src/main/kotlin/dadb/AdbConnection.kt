package dadb

class AdbConnection internal constructor(
        private val messageQueue: AdbMessageQueue,
        private val adbWriter: AdbWriter,
        private val localId: Int,
        private val remoteId: Int
) : AutoCloseable {

    override fun close() {
        adbWriter.writeClose(localId, remoteId)
        messageQueue.take(localId, Constants.CMD_CLSE)
        adbWriter.close()
        messageQueue.close()
    }
}
