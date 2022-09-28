package dadb

import java.net.Socket

internal class DadbTestImpl : DadbTest() {

    override fun localEmulator(body: (dadb: Dadb) -> Unit) {
        val socket = Socket("localhost", 5555)
        val keyPair = AdbKeyPair.readDefault()
        val connection = AdbConnection.connect(socket, keyPair)
        TestDadb(connection).use(body)
        connection.ensureEmpty()
    }

    private class TestDadb(
        private val connection: AdbConnection,
    ) : Dadb {

        override fun open(destination: String) = connection.open(destination)

        override fun supportsFeature(feature: String) = connection.supportsFeature(feature)

        override fun close() = connection.close()
    }
}