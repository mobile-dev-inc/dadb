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

/**
 * Thrown by the opt-in `orThrow()` helpers when an operation returned a `Failure`. This is an
 * *operation outcome* surfaced as an exception by caller choice — the transport is healthy.
 *
 * It is deliberately **not** an [AdbException]: a `catch (AdbException)` that exists to reconnect on
 * transport death will not catch this, so the two axes (transport died vs. operation rejected) stay
 * distinct even for callers who opt into the throwing convenience. Catch it explicitly when you use
 * `orThrow()`.
 *
 * [exitCode] is populated only for operations whose failure carries one (e.g. `uninstall`).
 */
class AdbOperationFailedException(
    val reason: String,
    val exitCode: Int? = null,
) : RuntimeException(
    if (exitCode != null) "Operation failed (exit $exitCode): $reason" else "Operation failed: $reason"
)
