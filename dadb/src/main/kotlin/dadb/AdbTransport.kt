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

import okio.Sink
import okio.Source
import java.io.IOException

/**
 * A low-level transport carrying raw ADB packets.
 *
 * Socket is the default implementation, but callers can provide transports backed by
 * Android USB host APIs, tunnels, in-process bridges, or any other bidirectional channel.
 */
interface AdbTransport : AutoCloseable {

    val source: Source

    val sink: Sink

    val isClosed: Boolean

    val description: String
        get() = javaClass.name
}

fun interface AdbTransportFactory {

    val description: String
        get() = javaClass.name

    @Throws(IOException::class)
    fun connect(): AdbTransport
}
