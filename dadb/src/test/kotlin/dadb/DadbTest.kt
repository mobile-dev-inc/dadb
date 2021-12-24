/*
 * Copyright (c) 2021 mobile.dev inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package dadb

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import okio.source
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test

internal class DadbTest : BaseConcurrencyTest() {

    @JvmField
    @Rule
    val temporaryFolder = TemporaryFolder()

    @BeforeTest
    fun setUp() {
        temporaryFolder.create()
        killServer()
    }

    @Test
    fun basic() {
        localEmulator { dadb ->
            dadb.open("shell,raw:echo hello").use { stream ->
                val response = stream.source.readString(Charsets.UTF_8)
                assertThat(response).isEqualTo("hello\n")
            }
        }
    }

    @Test
    fun openShell_read() {
        localEmulator { dadb ->
            dadb.openShell("echo hello").use { shellStream ->
                val shellResponse = shellStream.readAll()
                assertShellResponse(shellResponse, 0, "hello\n")
            }
        }
    }

    @Test
    fun openShell_write() {
        localEmulator { dadb ->
            dadb.openShell().use { shellStream ->
                shellStream.write("echo hello\n")

                val shellPacket = shellStream.read()
                assertShellPacket(shellPacket, ID_STDOUT, "hello\n")

                shellStream.write("exit\n")

                val shellResponse = shellStream.readAll()
                assertShellResponse(shellResponse, 0, "")
            }
        }
    }

    @Test
    fun openShell_concurrency() {
        localEmulator { dadb ->
            launch(20) {
                val random = randomString()
                dadb.openShell().use { shellStream ->
                    shellStream.write("echo $random\n")

                    val shellPacket = shellStream.read()
                    assertShellPacket(shellPacket, ID_STDOUT, "$random\n")

                    shellStream.write("exit\n")

                    val shellResponse = shellStream.readAll()
                    assertShellResponse(shellResponse, 0, "")
                }
            }
            waitForAll()
        }
    }

    @Test
    fun adbPush_basic() {
        localEmulator { dadb ->
            val content = randomString()
            val remotePath = "/data/local/tmp/hello"

            val source = ByteArrayInputStream(content.toByteArray()).source()
            dadb.push(source, remotePath, 439, System.currentTimeMillis())

            val buffer = Buffer()
            dadb.pull(buffer, remotePath)
            val pulledContent = buffer.readString(StandardCharsets.UTF_8)

            assertThat(pulledContent).isEqualTo(content)
        }
    }

    @Test
    fun adbPush_directoryDoesNotExist() {
        localEmulator { dadb ->
            val content = randomString()
            val remoteDir = "/data/local/tmp/nested"
            val remotePath = "$remoteDir/hello"

            dadb.shell("rm -rf $remoteDir")

            val source = ByteArrayInputStream(content.toByteArray()).source()
            dadb.push(source, remotePath, 439, System.currentTimeMillis())

            val buffer = Buffer()
            dadb.pull(buffer, remotePath)
            val pulledContent = buffer.readString(StandardCharsets.UTF_8)

            assertThat(pulledContent).isEqualTo(content)
        }
    }

    @Test
    fun adbPush_file() {
        localEmulator { dadb ->
            val content = randomString()
            val remotePath = "/data/local/tmp/hello"
            val localSrcFile = temporaryFolder.newFile().apply { writeText(content) }

            dadb.push(localSrcFile, remotePath, 439, System.currentTimeMillis())

            val localDstFile = temporaryFolder.newFile()
            dadb.pull(localDstFile, remotePath)

            assertThat(localDstFile.readText()).isEqualTo(content)
        }
    }

    @Test
    fun install() {
        localEmulator { dadb ->
            dadb.install(TestApk.FILE)
            val response = dadb.shell("pm list packages ${TestApk.PACKAGE_NAME}")
            assertShellResponse(response, 0, "package:${TestApk.PACKAGE_NAME}\n")
        }
    }

    @Test
    internal fun install_grantPermissions() {
        localEmulator { dadb ->
            dadb.install(TestApk.FILE)
            var response = dadb.shell("dumpsys package ${TestApk.PACKAGE_NAME}")
            assertThat(response.exitCode).isEqualTo(0)
            assertThat(response.output).doesNotContain("android.permission.RECORD_AUDIO: granted=true")

            dadb.install(TestApk.FILE, "-g")
            response = dadb.shell("dumpsys package ${TestApk.PACKAGE_NAME}")
            assertThat(response.exitCode).isEqualTo(0)
            assertThat(response.output).contains("android.permission.RECORD_AUDIO: granted=true")
        }
    }

    @Test
    fun uninstall() {
        localEmulator { dadb ->
            dadb.install(TestApk.FILE)
            dadb.uninstall(TestApk.PACKAGE_NAME)
            val response = dadb.shell("pm list packages ${TestApk.PACKAGE_NAME}")
            assertShellResponse(response, 0, "")
        }
    }

    @Ignore
    @Test
    fun root() {
        localEmulator(Dadb::unroot)
        localEmulator(Dadb::root)
        localEmulator { dadb ->
            val response = dadb.shell("getprop service.adb.root")
            assertShellResponse(response, 0, "1\n")
        }
    }

    @Ignore
    @Test
    fun unroot() {
        localEmulator(Dadb::root)
        localEmulator(Dadb::unroot)
        localEmulator { dadb ->
            val response = dadb.shell("getprop service.adb.root")
            assertShellResponse(response, 0, "0\n")
        }
    }

    private fun localEmulator(body: (dadb: Dadb) -> Unit) {
        val socket = Socket("localhost", 5555)
        val keyPair = AdbKeyPair.readDefault()
        val connection = AdbConnection.connect(socket, keyPair)
        TestDadb(connection).use(body)
        connection.ensureEmpty()
    }

    private fun randomString(): String {
        return "${Random().nextDouble()}"
    }
}

private class TestDadb(
        private val connection: AdbConnection
) : Dadb {

    override fun open(destination: String) = connection.open(destination)

    override fun supportsFeature(feature: String) = connection.supportsFeature(feature)

    override fun close() = connection.close()
}
