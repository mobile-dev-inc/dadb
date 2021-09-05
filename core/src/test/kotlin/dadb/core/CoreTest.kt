package dadb.core

import okio.sink
import okio.source
import org.junit.Before
import java.net.Socket
import kotlin.test.Test

internal class CoreTest {

    @Before
    fun setUp() {
        // Connection fails if there are simultaneous auth requests
        Runtime.getRuntime().exec("adb kill-server").waitFor()
    }

    @Test
    fun name() {
        val socket = Socket("localhost", 5555)
        val adbSource = socket.source()
        val adbSink = socket.sink()
        val keyPair = AdbKeyPair.readDefault()
        println(AdbChannel.connect(adbSource, adbSink, keyPair))
    }
}
