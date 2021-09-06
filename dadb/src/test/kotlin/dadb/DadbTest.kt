package dadb

import com.google.common.truth.Truth
import org.junit.Before
import java.io.IOException
import java.net.Socket
import kotlin.random.Random
import kotlin.test.Test

internal class DadbTest : BaseConcurrencyTest() {

    @Before
    fun setUp() {
        killServer()
    }

    @Test
    fun basic() {
        useDefaultChannel { channel ->
            channel.open("shell,raw:echo hello").use { stream ->
                val response = stream.source.readString(Charsets.UTF_8)
                Truth.assertThat(response).isEqualTo("hello\n")
            }
        }
    }

    @Test
    fun shellV2_read() {
        useDefaultChannel { channel ->
            channel.shellV2("echo hello").use { shellStream ->
                val shellResponse = shellStream.readAll()
                assertShellResponse(shellResponse, 0, "hello\n")
            }
        }
    }

    @Test
    fun shellV2_write() {
        useDefaultChannel { channel ->
            channel.shellV2().use { shellStream ->
                shellStream.write("echo hello\n")

                val shellPacket = shellStream.read()
                assertShellPacket(shellPacket, Constants.SHELL_ID_STDOUT, "hello\n")

                shellStream.write("exit\n")

                val shellResponse = shellStream.readAll()
                assertShellResponse(shellResponse, 0, "")
            }
        }
    }

    @Test
    fun shellV2_concurrency() {
        useDefaultChannel { channel ->
            launch(20) {
                val random = Random.nextDouble()
                channel.shellV2().use { shellStream ->
                    shellStream.write("echo $random\n")

                    val shellPacket = shellStream.read()
                    assertShellPacket(shellPacket, Constants.SHELL_ID_STDOUT, "$random\n")

                    shellStream.write("exit\n")

                    val shellResponse = shellStream.readAll()
                    assertShellResponse(shellResponse, 0, "")
                }
            }
            waitForAll()
        }
    }

    private fun assertShellResponse(shellResponse: AdbShellResponse, exitCode: Int, allOutput: String) {
        Truth.assertThat(shellResponse.allOutput).isEqualTo(allOutput)
        Truth.assertThat(shellResponse.exitCode).isEqualTo(exitCode)
    }

    private fun assertShellPacket(shellPacket: AdbShellPacket, id: Int, payload: String) {
        Truth.assertThat(String(shellPacket.payload)).isEqualTo(payload)
        Truth.assertThat(shellPacket.id).isEqualTo(id)
    }

    private fun useDefaultChannel(body: (channel: AdbChannel) -> Unit) {
        val socket = Socket("localhost", 5555)
        val keyPair = AdbKeyPair.readDefault()
        val channel = AdbChannel.connect(socket, keyPair)
        channel.use(body)
        channel.ensureEmpty()
    }

    private fun killServer() {
        try {
            // Connection fails if there are simultaneous auth requests
            Runtime.getRuntime().exec("adb kill-server").waitFor()
        } catch (ignore: IOException) {}
    }
}
