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
import okio.Buffer
import okio.Sink
import okio.Timeout
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class AdbWriterTest {

    // A sink that fails every write the way okio's socket write-timeout does on a wedged adbd.
    private class ThrowingSink(private val error: IOException) : Sink {
        override fun write(source: Buffer, byteCount: Long) { throw error }
        override fun flush() {}
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() {}
    }

    @Test
    fun payloadWrite_onWriteTimeout_surfacesAsAdbException() {
        // The wedged-push path: AdbStream.sink.flush() -> writeWrite(...). Before the fix this leaked
        // okio's raw SocketTimeoutException past push()/install(), which are declared @Throws(AdbException).
        val cause = SocketTimeoutException("timeout")
        val writer = AdbWriter(ThrowingSink(cause))

        val thrown = assertFailsWith<AdbConnectionClosedException> {
            writer.writeWrite(localId = 1, remoteId = 2, payload = ByteArray(8), offset = 0, length = 8)
        }
        assertThat(thrown).isInstanceOf(AdbException::class.java)
        assertThat(thrown.cause).isSameInstanceAs(cause)
    }

    @Test
    fun ackWrite_onTransportFault_surfacesAsAdbException() {
        // The read-path ack: AdbStream.source.read() -> writeOkay(...) sits outside nextMessage's
        // catch, so it leaked raw too. Same single funnel, same guarantee.
        val writer = AdbWriter(ThrowingSink(IOException("broken pipe")))

        assertFailsWith<AdbConnectionClosedException> {
            writer.writeOkay(localId = 1, remoteId = 2)
        }
    }
}
