package dadb.adbserver

import com.google.common.truth.Truth.assertThat
import dadb.killServer
import kotlin.system.measureTimeMillis
import kotlin.test.Test

internal class AdbBinaryTest {

    @Test
    fun ensureServerRunning() {
        repeat(10) {
            killServer()
            AdbBinary.ensureServerRunning("localhost", 5037)
            val output = AdbServer.createDadb("localhost", 5037).shell("echo hello").allOutput
            assertThat(output).isEqualTo("hello\n")
        }
    }

    @Test
    fun ensureServerRunning_performance() {
        AdbBinary.ensureServerRunning("localhost", 5037)
        val time = measureTimeMillis {
            repeat(100) {
                AdbBinary.ensureServerRunning("localhost", 5037)
            }
        }
        assertThat(time).isLessThan(100)
    }
}
