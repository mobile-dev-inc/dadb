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
import okio.buffer
import okio.source
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test

internal abstract class DadbTest : BaseConcurrencyTest() {

    private val remotePath = "/data/local/tmp/hello"

    @JvmField
    @Rule
    val temporaryFolder = TemporaryFolder()

    val executor = Executors.newCachedThreadPool()

    @BeforeTest
    fun setUp() {
        temporaryFolder.create()
    }

    @AfterTest
    internal fun tearDown() {
        localEmulator { dadb ->
            dadb.shell("rm -f $remotePath")
        }
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
                assertShellPacket(shellPacket, AdbShellPacket.StdOut::class.java, "hello\n")

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
                    assertShellPacket(shellPacket, AdbShellPacket.StdOut::class.java, "$random\n")

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
    fun adbPull_largeFile() {
        localEmulator { dadb ->
            val sizeMb = 100

            dadb.shell("fallocate -l ${sizeMb}M $remotePath")

            val buffer = Buffer()
            dadb.pull(buffer, remotePath)
            val pulledContent = buffer.readByteArray()

            assertThat(pulledContent).hasLength(sizeMb * 1024 * 1024)
        }
    }

    @Test
    fun adbPush_file() {
        localEmulator { dadb ->
            val content = randomString()
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
    fun installStream() {
        localEmulator { dadb ->
            val inputStream = FileInputStream(TestApk.FILE).source()
            dadb.install(inputStream, TestApk.FILE.length())
            val response = dadb.shell("pm list packages ${TestApk.PACKAGE_NAME}")
            assertShellResponse(response, 0, "package:${TestApk.PACKAGE_NAME}\n")
        }
    }

    @Test
    fun installMultiple() {
        localEmulator { dadb ->
            dadb.installMultiple(TestApk.SPLIT_FILES)
            val response = dadb.shell("pm path ${TestApk.SPLIT_PACKAGE_NAME} | wc -l")
            assertShellResponse(response, 0, "${TestApk.SPLIT_FILES.size}\n")
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

    @Test
    fun tcpForward_singleConnection() {
        localEmulator { dadb ->
            dadb.tcpForward(8888, 8888).use { _ ->
                val future = broadcastSingleMessage(dadb, "OK", 8888)
                val result = readSocket("localhost", 8888)

                future.get(1, TimeUnit.SECONDS)

                assertThat(result).isEqualTo("OK\n")
            }
        }
    }

    @Test
    fun tcpForward_multipleSequentialConnections() {
        localEmulator { dadb ->
            dadb.tcpForward(8888, 8888).use { _ ->
                val firstFuture = broadcastSingleMessage(dadb, "OK", 8888)
                val first = readSocket("localhost", 8888)

                val secondFuture = broadcastSingleMessage(dadb, "OK", 8888)
                val second = readSocket("localhost", 8888)

                firstFuture.get(1, TimeUnit.SECONDS)
                secondFuture.get(1, TimeUnit.SECONDS)

                assertThat(first).isEqualTo("OK\n")
                assertThat(second).isEqualTo("OK\n")
            }
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

    @Test
    fun unicode() {
        localEmulator { dadb ->
            dadb.openShell("echo bénéficiaire").use { shellStream ->
                val shellResponse = shellStream.readAll()
                assertShellResponse(shellResponse, 0, "bénéficiaire\n")
            }
        }
    }

    protected abstract fun localEmulator(body: (dadb: Dadb) -> Unit)

    private fun readSocketAsync(host: String, port: Int): Future<String> {
        return executor
            .submit(Callable {
                Socket(host, port).use { socket ->
                    socket.source()
                        .buffer()
                        .readUtf8()
                }
            })
    }

    private fun readSocket(host: String, port: Int): String {
        return readSocketAsync(host, port)
            .get(5, TimeUnit.SECONDS)
    }

    private fun broadcastSingleMessage(dadb: Dadb, message: String, port: Int): Future<*> {
        val future = executor.submit {
            dadb.shell("echo -e '$message' | nc -lp $port")
        }
        Thread.sleep(500)

        return future
    }

    private fun randomString(): String {
        return "${Random().nextDouble()}"
    }
}
