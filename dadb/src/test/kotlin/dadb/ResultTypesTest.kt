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

internal class ResultTypesTest {

    @Test
    fun installResultVariants() {
        val ok: InstallResult = InstallResult.Success
        val fail: InstallResult = InstallResult.Failure("Failure [INSTALL_FAILED_INVALID_APK]")
        assertThat(ok).isInstanceOf(InstallResult.Success::class.java)
        assertThat((fail as InstallResult.Failure).reason).contains("INSTALL_FAILED")
    }

    @Test
    fun uninstallFailureCarriesExitCodeAndReason() {
        val fail = UninstallResult.Failure(reason = "Failure [DELETE_FAILED_INTERNAL_ERROR]", exitCode = 1)
        assertThat(fail.exitCode).isEqualTo(1)
        assertThat(fail.reason).contains("DELETE_FAILED")
    }

    @Test
    fun syncAndRootVariants() {
        assertThat(SyncResult.Failure("No such file or directory").reason).contains("No such file")
        assertThat(RootResult.Failure("adbd cannot run as root in production builds").reason)
            .contains("production builds")
        val syncOk: SyncResult = SyncResult.Success
        val rootOk: RootResult = RootResult.Success
        assertThat(syncOk).isInstanceOf(SyncResult.Success::class.java)
        assertThat(rootOk).isInstanceOf(RootResult.Success::class.java)
    }
}
