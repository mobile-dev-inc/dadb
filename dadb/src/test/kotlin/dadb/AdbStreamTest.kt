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

import com.google.common.truth.Truth
import okio.Buffer
import kotlin.test.Test

internal class AdbStreamTest {

    @Test
    fun testLargeRemoteWrite() {
        val payload = ByteArray(1024 * 1024).apply { fill(1) }
        val adbReader = createAdbReader(1, 2, payload)
        val messageQueue = AdbMessageQueue(adbReader)
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(messageQueue, AdbWriter(Buffer()), 1024, 1, 2)
        Truth.assertThat(stream.source.readByteArray()).isEqualTo(payload)
    }

    private fun createAdbReader(localId: Int, remoteId: Int, writePayload: ByteArray): AdbReader {
        val source = Buffer()
        AdbWriter(source).write(Constants.CMD_WRTE, remoteId, localId, writePayload, 0, writePayload.size)
        return AdbReader(source)
    }
}