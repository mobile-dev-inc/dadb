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

import okio.Buffer
import java.nio.charset.StandardCharsets

/** An [AdbStream] backed by in-memory buffers. [source] is pre-loaded with device bytes;
 *  [sink] captures whatever the code under test writes. */
internal class FakeAdbStream(
    override val source: Buffer,
    override val sink: Buffer = Buffer()
) : AdbStream {
    override fun close() {}
}

/** A [Dadb] whose `open(destination)` returns a caller-supplied [AdbStream]. Records every
 *  destination opened, in order, in [opened]. */
internal class FakeDadb(
    private val features: Set<String> = setOf("shell_v2", "cmd"),
    private val streamFor: (destination: String) -> AdbStream
) : Dadb {
    val opened = mutableListOf<String>()

    override fun open(destination: String): AdbStream {
        opened += destination
        return streamFor(destination)
    }

    override fun supportsFeature(feature: String) = feature in features

    override fun close() {}
}

/** Builds a shell,v2 byte stream: optional stdout/stderr packets followed by an exit packet. */
internal fun shellV2Buffer(stdout: String = "", stderr: String = "", exitCode: Int): Buffer {
    val buffer = Buffer()
    if (stdout.isNotEmpty()) {
        val bytes = stdout.toByteArray()
        buffer.writeByte(ID_STDOUT); buffer.writeIntLe(bytes.size); buffer.write(bytes)
    }
    if (stderr.isNotEmpty()) {
        val bytes = stderr.toByteArray()
        buffer.writeByte(ID_STDERR); buffer.writeIntLe(bytes.size); buffer.write(bytes)
    }
    buffer.writeByte(ID_EXIT); buffer.writeIntLe(1); buffer.writeByte(exitCode)
    return buffer
}

/** Builds a single sync `FAIL` packet (4-char id + LE length + message). */
internal fun syncFailBuffer(message: String): Buffer {
    val buffer = Buffer()
    buffer.writeString("FAIL", StandardCharsets.UTF_8)
    buffer.writeIntLe(message.toByteArray().size)
    buffer.writeString(message, StandardCharsets.UTF_8)
    return buffer
}

/** Builds a single sync `OKAY` packet (success terminator for SEND). */
internal fun syncOkayBuffer(): Buffer {
    val buffer = Buffer()
    buffer.writeString("OKAY", StandardCharsets.UTF_8)
    buffer.writeIntLe(0)
    return buffer
}
