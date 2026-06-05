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

/** Outcome of [Dadb.install] / [Dadb.installMultiple]. */
sealed interface InstallResult {
    object Success : InstallResult
    /** Raw pm/`cmd package` verdict, surfaced opaquely — exactly as the canonical adb client does
     *  (client/adb_install.cpp checks `strncmp("Success", buf, 7)` and prints the rest verbatim).
     *  `INSTALL_FAILED_*` codes are a frameworks/base concept, not the adbd contract, so they are
     *  not parsed into an enum. */
    data class Failure(val reason: String) : InstallResult
}
