package dadb.adbserver

import dadb.killServer
import kotlin.test.Test

internal class AdbBinaryTest {

    @Test
    fun test() {
        killServer()
        AdbBinary.ensureServerRunning("localhost", 5037)
    }
}