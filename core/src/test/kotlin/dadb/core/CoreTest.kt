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
        // AdbProtocol.sendMessage(outputStream, messageBuffer, CMD_CNXN, CONNECT_VERSION, CONNECT_MAXDATA, CONNECT_PAYLOAD, 0, CONNECT_PAYLOAD.length);
        val adbWriter = AdbWriter(adbSink)
        val adbReader = AdbReader(adbSource)
        adbWriter.write(
                Constants.CMD_CNXN,
                Constants.CONNECT_VERSION,
                Constants.CONNECT_MAXDATA,
                Constants.CONNECT_PAYLOAD,
                0,
                Constants.CONNECT_PAYLOAD.size
        )
        println(adbReader.readMessage())
    }
}
