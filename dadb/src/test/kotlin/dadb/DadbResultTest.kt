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
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class DadbResultTest {

    @Test
    fun syncRecvFailThrowsSyncFailException() {
        val stream = FakeAdbStream(syncFailBuffer("No such file or directory"))
        val sync = AdbSyncStream(stream)
        val e = assertFailsWith<AdbSyncFailException> { sync.recv(Buffer(), "/missing") }
        assertThat(e.reason).isEqualTo("No such file or directory")
    }

    @Test
    fun pullMissingFileReturnsSyncFailure() {
        val dadb = FakeDadb { FakeAdbStream(syncFailBuffer("No such file or directory")) }
        val result = dadb.pull(Buffer(), "/missing")
        assertThat(result).isInstanceOf(SyncResult.Failure::class.java)
        assertThat((result as SyncResult.Failure).reason).isEqualTo("No such file or directory")
    }

    @Test
    fun pushSuccessReturnsSyncSuccess() {
        val dadb = FakeDadb { FakeAdbStream(syncOkayBuffer()) }
        val result = dadb.push(Buffer().also { it.writeUtf8("hi") }, "/data/local/tmp/hi", 0b110_100_100, 0L)
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)
    }

    @Test
    fun uninstallNonZeroExitReturnsFailureWithExitCode() {
        val dadb = FakeDadb {
            FakeAdbStream(shellV2Buffer(stdout = "Failure [DELETE_FAILED_INTERNAL_ERROR]\n", exitCode = 1))
        }
        val result = dadb.uninstall("com.example.absent")
        assertThat(result).isInstanceOf(UninstallResult.Failure::class.java)
        result as UninstallResult.Failure
        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.reason).contains("DELETE_FAILED_INTERNAL_ERROR")
    }

    @Test
    fun uninstallZeroExitReturnsSuccess() {
        val dadb = FakeDadb { FakeAdbStream(shellV2Buffer(stdout = "Success\n", exitCode = 0)) }
        assertThat(dadb.uninstall("com.example")).isInstanceOf(UninstallResult.Success::class.java)
    }

    @Test
    fun installCmdPathFailureReturnsInstallFailure() {
        val dadb = FakeDadb(features = setOf("cmd")) {
            FakeAdbStream(Buffer().also { it.writeUtf8("Failure [INSTALL_FAILED_INVALID_APK]") })
        }
        val result = dadb.install(Buffer().also { it.writeUtf8("apk") }, 3L)
        assertThat(result).isInstanceOf(InstallResult.Failure::class.java)
        assertThat((result as InstallResult.Failure).reason).contains("INSTALL_FAILED_INVALID_APK")
    }

    @Test
    fun installCmdPathSuccessReturnsSuccess() {
        val dadb = FakeDadb(features = setOf("cmd")) {
            FakeAdbStream(Buffer().also { it.writeUtf8("Success\n") })
        }
        val result = dadb.install(Buffer().also { it.writeUtf8("apk") }, 3L)
        assertThat(result).isInstanceOf(InstallResult.Success::class.java)
    }

    @Test
    fun rootFailureReturnsRootFailure() {
        val dadb = FakeDadb {
            FakeAdbStream(Buffer().also { it.writeUtf8("adbd cannot run as root in production builds\n") })
        }
        val result = dadb.root()
        assertThat(result).isInstanceOf(RootResult.Failure::class.java)
        assertThat((result as RootResult.Failure).reason).contains("production builds")
    }
}
