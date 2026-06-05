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

/** Outcome of [Dadb.root] / [Dadb.unroot]. [Failure.reason] is the adbd root:/unroot: response line. */
sealed interface RootResult {
    object Success : RootResult
    data class Failure(val reason: String) : RootResult
}

/** Run [block] if this is a [RootResult.Success]; returns the receiver for chaining. */
inline fun RootResult.onSuccess(block: () -> Unit): RootResult {
    if (this is RootResult.Success) block()
    return this
}

/** Run [block] if this is a [RootResult.Failure]; returns the receiver for chaining. */
inline fun RootResult.onFailure(block: (RootResult.Failure) -> Unit): RootResult {
    if (this is RootResult.Failure) block(this)
    return this
}

/** Fail-fast: throw [AdbOperationFailedException] if this is a [RootResult.Failure]. */
fun RootResult.orThrow() {
    if (this is RootResult.Failure) throw AdbOperationFailedException(reason)
}
