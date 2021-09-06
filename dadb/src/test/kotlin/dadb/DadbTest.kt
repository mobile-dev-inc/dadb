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
        useDefaultConnection { connection ->
            connection.open("shell,raw:echo hello").use { stream ->
                val response = stream.source.readString(Charsets.UTF_8)
                Truth.assertThat(response).isEqualTo("hello\n")
            }
        }
    }

    @Test
    fun shellV2_read() {
        useDefaultConnection { connection ->
            connection.shellV2("echo hello").use { shellStream ->
                val shellResponse = shellStream.readAll()
                assertShellResponse(shellResponse, 0, "hello\n")
            }
        }
    }

    @Test
    fun shellV2_write() {
        useDefaultConnection { connection ->
            connection.shellV2().use { shellStream ->
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
        useDefaultConnection { connection ->
            launch(20) {
                val random = Random.nextDouble()
                connection.shellV2().use { shellStream ->
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

    private fun useDefaultConnection(body: (connection: AdbConnection) -> Unit) {
        val socket = Socket("localhost", 5555)
        val keyPair = AdbKeyPair.readDefault()
        val connection = AdbConnection.connect(socket, keyPair)
        connection.use(body)
        connection.ensureEmpty()
    }

    private fun killServer() {
        try {
            // Connection fails if there are simultaneous auth requests
            Runtime.getRuntime().exec("adb kill-server").waitFor()
        } catch (ignore: IOException) {}
    }
}
