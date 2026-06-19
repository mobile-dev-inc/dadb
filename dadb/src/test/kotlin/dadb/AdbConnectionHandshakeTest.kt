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
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class AdbConnectionHandshakeTest {

    @Test
    fun nonCnxnResponseThrowsConnectException() {
        // Device replies with an unexpected OKAY instead of CNXN.
        val device = Buffer()
        AdbWriter(device).writeOkay(0, 0)
        assertFailsWith<AdbConnectException> {
            AdbConnection.connect(device, Buffer(), null)
        }
    }

    @Test
    fun authRequiredWithoutKeyPairThrowsAuthException() {
        // Device demands auth; we have no key pair.
        val device = Buffer()
        AdbWriter(device).writeAuth(Constants.AUTH_TYPE_TOKEN, ByteArray(20))
        assertFailsWith<AdbAuthException> {
            AdbConnection.connect(device, Buffer(), null)
        }
    }

    @Test
    fun truncatedHandshakeThrowsConnectException() {
        // Empty device stream: the first readMessage hits EOF.
        assertFailsWith<AdbConnectException> {
            AdbConnection.connect(Buffer(), Buffer(), null)
        }
    }
}
