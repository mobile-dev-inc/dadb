package dadb

import dadb.adbserver.AdbServerDadb
import org.junit.jupiter.api.BeforeEach

internal class AdbServerDadbTest : DadbTest() {

    @BeforeEach
    override fun setUp() {
        super.setUp()
        startServer()
    }

    override fun localEmulator(body: (dadb: Dadb) -> Unit) {
        AdbServerDadb.create().use(body)
    }
}