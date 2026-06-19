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

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Root of all dadb transport/connection failures. Extends [IOException] because these are genuine,
 * recoverable socket I/O faults, consistent with the okio stream surface dadb exposes. Every
 * transport fault thrown from a public method is one of these subtypes — no bare IOException leaks.
 */
sealed class AdbException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Could not establish the connection: TCP connect/timeout, or the A_CNXN handshake failed.
 *  Nothing ran — safe to retry by reconnecting. */
class AdbConnectException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** A_AUTH was rejected (no key, or device left UNAUTHORIZED). Reconnecting with the same key will
 *  not help — fix or accept the key. */
class AdbAuthException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** adbd refused to OPEN the stream (A_OPEN answered with A_CLSE): unknown/forbidden service string,
 *  or device offline. The underlying connection is still usable. */
class AdbStreamOpenException(
    val destination: String,
    message: String,
    cause: Throwable? = null
) : AdbException(message, cause)

/** An established connection/stream died unexpectedly (socket EOF/RST mid-operation). Reconnect —
 *  but the operation MAY have partially executed; re-check state before retrying non-idempotent work. */
class AdbConnectionClosedException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** An operation exceeded its deadline with adbd unresponsive: a read past socketTimeout, or a write
 *  past the write timeout (okio closes the socket on expiry). Distinct from a peer-driven EOF/RST.
 *  Reconnect; like a dropped connection the operation may have partially executed. */
class AdbTimeoutException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** The peer sent a malformed or unexpected apacket (desync). No reliable way to re-sync — the
 *  connection must be closed and re-established. */
class AdbProtocolException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** True if a SocketTimeoutException is anywhere in this throwable's cause chain. The write timeout
 *  arrives raw from okio; the read timeout arrives wrapped through the message queue. */
internal fun Throwable.causedByTimeout(): Boolean =
    generateSequence(this) { it.cause }.any { it is SocketTimeoutException }
