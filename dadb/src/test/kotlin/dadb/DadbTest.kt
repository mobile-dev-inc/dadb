package dadb

import org.junit.Before
import java.io.IOException
import java.net.Socket
import kotlin.test.Test

internal class DadbTest {

    @Before
    fun setUp() {
        killServer()
    }

    @Test
    fun name() {
        val socket = Socket("localhost", 5555)
        val keyPair = AdbKeyPair.readDefault()
        val channel = AdbChannel.open(socket, keyPair)
        channel.connect()
        channel.close()
    }

    private fun killServer() {
        try {
            // Connection fails if there are simultaneous auth requests
            Runtime.getRuntime().exec("adb kill-server").waitFor()
        } catch (ignore: IOException) {}
    }
}
