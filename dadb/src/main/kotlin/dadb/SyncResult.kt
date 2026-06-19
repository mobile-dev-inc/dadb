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

/** Outcome of [Dadb.push] / [Dadb.pull]. [Failure.reason] is the adbd sync FAIL message. */
sealed interface SyncResult {
    object Success : SyncResult
    data class Failure(val reason: String) : SyncResult
}

/** Run [block] if this is a [SyncResult.Success]; returns the receiver for chaining. */
inline fun SyncResult.onSuccess(block: () -> Unit): SyncResult {
    if (this is SyncResult.Success) block()
    return this
}

/** Run [block] if this is a [SyncResult.Failure]; returns the receiver for chaining. */
inline fun SyncResult.onFailure(block: (SyncResult.Failure) -> Unit): SyncResult {
    if (this is SyncResult.Failure) block(this)
    return this
}

/** Fail-fast: throw [AdbOperationFailedException] if this is a [SyncResult.Failure]. */
fun SyncResult.orThrow() {
    if (this is SyncResult.Failure) throw AdbOperationFailedException(reason)
}
