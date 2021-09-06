package dadb

import com.google.common.truth.Truth
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
    fun basic() {
        useDefaultChannel { channel ->
            channel.connect("shell,raw:echo hello").use { connection ->
                val response = connection.source.readString(Charsets.UTF_8)
                Truth.assertThat(response).isEqualTo("hello\n")
            }
        }
    }

    @Test
    fun shellV2_read() {
        useDefaultChannel { channel ->
            val connection = channel.connect("shell,v2,raw:echo hello")
            val shellConnection = AdbShellConnection(connection)
            val shellResponse = shellConnection.readAll()
            Truth.assertThat(shellResponse.allOutput).isEqualTo("hello\n")
            Truth.assertThat(shellResponse.exitCode).isEqualTo(0)
        }
    }

    private fun useDefaultChannel(body: (channel: AdbChannel) -> Unit) {
        val socket = Socket("localhost", 5555)
        val keyPair = AdbKeyPair.readDefault()
        AdbChannel.open(socket, keyPair).use(body)
    }

    private fun killServer() {
        try {
            // Connection fails if there are simultaneous auth requests
            Runtime.getRuntime().exec("adb kill-server").waitFor()
        } catch (ignore: IOException) {}
    }
}
