package dadb

import dadb.adbserver.AdbServerDadb

internal class AdbServerDadbTest : DadbTest() {

    override fun localEmulator(body: (dadb: Dadb) -> Unit) {
        AdbServerDadb.create("localhost", 5037).use(body)
    }
}