package dadb

import org.junit.Before
import java.net.Socket
import kotlin.test.Test

internal class DadbTest {

    @Before
    fun setUp() {
        // Connection fails if there are simultaneous auth requests
        Runtime.getRuntime().exec("adb kill-server").waitFor()
    }

    @Test
    fun name() {
        val socket = Socket("localhost", 5555)
        val keyPair = AdbKeyPair.readDefault()
        AdbChannel.connect(socket, keyPair).close()
    }
}
