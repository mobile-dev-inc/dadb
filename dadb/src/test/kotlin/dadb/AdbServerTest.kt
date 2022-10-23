package dadb

import com.google.common.truth.Truth.assertThat
import dadb.adbserver.AdbServer

import kotlin.test.Test

internal class AdbServerTest : DadbTest() {

    @Test
    internal fun stoppedServer() {
        killServer()
        localEmulator { dadb ->
            val output = dadb.shell("echo hello").allOutput
            assertThat(output).isEqualTo("hello\n")
        }
    }

    override fun localEmulator(body: (dadb: Dadb) -> Unit) {
        AdbServer.createDadb("localhost", 5037).use(body)
    }
}