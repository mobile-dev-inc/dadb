package dadb.core

import okio.sink
import okio.source
import java.net.Socket
import kotlin.test.Test

internal class CoreTest {

    @Test
    fun name() {
        val socket = Socket("localhost", 5555)
        val adbSource = socket.source()
        val adbSink = socket.sink()
        val keyPair = AuthUtils.readDefaultKeyPair()
        println(AdbChannel.connect(adbSource, adbSink, keyPair))
    }
}
