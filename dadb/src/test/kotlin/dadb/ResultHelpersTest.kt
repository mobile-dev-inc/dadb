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
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class ResultHelpersTest {

    @Test
    fun onSuccessRunsOnlyForSuccess() {
        var ran = false
        (InstallResult.Success as InstallResult).onSuccess { ran = true }
        assertThat(ran).isTrue()

        ran = false
        (InstallResult.Failure("nope") as InstallResult).onSuccess { ran = true }
        assertThat(ran).isFalse()
    }

    @Test
    fun onFailureReceivesTheFailurePayload() {
        var reason: String? = null
        (SyncResult.Failure("No such file or directory") as SyncResult).onFailure { reason = it.reason }
        assertThat(reason).isEqualTo("No such file or directory")

        reason = "untouched"
        (SyncResult.Success as SyncResult).onFailure { reason = it.reason }
        assertThat(reason).isEqualTo("untouched")
    }

    @Test
    fun helpersReturnReceiverForChaining() {
        var sawFailure = false
        val result: InstallResult = InstallResult.Failure("rejected")
        result.onSuccess { error("should not run") }.onFailure { sawFailure = true }
        assertThat(sawFailure).isTrue()
    }

    @Test
    fun orThrowIsNoOpOnSuccess() {
        (RootResult.Success as RootResult).orThrow()
    }

    @Test
    fun orThrowRaisesNonAdbExceptionOnFailure() {
        val e = assertFailsWith<AdbOperationFailedException> {
            (InstallResult.Failure("Failure [INSTALL_FAILED_INVALID_APK]") as InstallResult).orThrow()
        }
        assertThat(e.reason).contains("INSTALL_FAILED")
        // The whole point: an opted-in operation throw is NOT a transport fault.
        assertThat(e).isNotInstanceOf(AdbException::class.java)
    }

    @Test
    fun uninstallOrThrowCarriesExitCode() {
        val e = assertFailsWith<AdbOperationFailedException> {
            (UninstallResult.Failure(reason = "DELETE_FAILED_INTERNAL_ERROR", exitCode = 1) as UninstallResult).orThrow()
        }
        assertThat(e.exitCode).isEqualTo(1)
    }
}
