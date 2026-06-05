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

/** Outcome of [Dadb.uninstall]. `uninstall` is `cmd package uninstall` over the shell service, so
 *  [Failure] preserves adbd's actual process exit code and combined output — the wrapper hides nothing. */
sealed interface UninstallResult {
    object Success : UninstallResult
    data class Failure(val reason: String, val exitCode: Int) : UninstallResult
}

/** Run [block] if this is a [UninstallResult.Success]; returns the receiver for chaining. */
inline fun UninstallResult.onSuccess(block: () -> Unit): UninstallResult {
    if (this is UninstallResult.Success) block()
    return this
}

/** Run [block] if this is a [UninstallResult.Failure]; returns the receiver for chaining. */
inline fun UninstallResult.onFailure(block: (UninstallResult.Failure) -> Unit): UninstallResult {
    if (this is UninstallResult.Failure) block(this)
    return this
}

/** Fail-fast: throw [AdbOperationFailedException] (carrying the process [exitCode]) if this is a
 *  [UninstallResult.Failure]. */
fun UninstallResult.orThrow() {
    if (this is UninstallResult.Failure) throw AdbOperationFailedException(reason, exitCode)
}
