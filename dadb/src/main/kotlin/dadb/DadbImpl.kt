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

import org.jetbrains.annotations.TestOnly
import java.net.Socket

internal class DadbImpl(
        private val host: String,
        private val port: Int,
        private val keyPair: AdbKeyPair? = null
) : Dadb {

    private var connection: Pair<AdbConnection, Socket>? = null

    override fun open(destination: String) = connection().open(destination)

    override fun close() {
        connection?.first?.close()
    }
    override fun toString() = "$host:$port"

    @TestOnly
    fun closeConnection() {
        connection?.second?.close()
    }

    @Synchronized
    private fun connection(): AdbConnection {
        var connection = connection
        if (connection == null || connection.second.isClosed) {
            connection = newConnection()
            this.connection = connection
        }
        return connection.first
    }

    private fun newConnection(): Pair<AdbConnection, Socket> {
        val socket = Socket(host, port)
        val adbConnection = AdbConnection.connect(socket, keyPair)
        return adbConnection to socket
    }
}
