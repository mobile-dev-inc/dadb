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

import com.google.common.truth.Truth
import org.junit.Before
import java.net.Socket
import kotlin.random.Random
import kotlin.test.Test

internal class AdbConnectionTest : BaseConcurrencyTest() {

    @Before
    fun setUp() {
        killServer()
    }

    @Test
    fun basic() {
        localEmulator { dadb ->
            dadb.open("shell,raw:echo hello").use { stream ->
                val response = stream.source.readString(Charsets.UTF_8)
                Truth.assertThat(response).isEqualTo("hello\n")
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
                val random = Random.nextDouble()
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
    fun install() {
        localEmulator { dadb ->
            dadb.install(TestApk.FILE)
            val response = dadb.shell("pm list packages ${TestApk.PACKAGE_NAME}")
            assertShellResponse(response, 0, "package:${TestApk.PACKAGE_NAME}\n")
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
    fun root() {
        localEmulator { dadb ->
            dadb.unroot()
        }
        localEmulator { dadb ->
            dadb.root()
        }
    }

    @Test
    fun unroot() {
        localEmulator { dadb ->
            dadb.root()
        }
        localEmulator { dadb ->
            dadb.unroot()
        }
    }

    private fun localEmulator(body: (dadb: Dadb) -> Unit) {
        val socket = Socket("localhost", 5555)
        val keyPair = AdbKeyPair.readDefault()
        val connection = AdbConnection.connect(socket, keyPair)
        connection.use(body)
        connection.ensureEmpty()
    }
}
