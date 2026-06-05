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
import java.io.IOException
import kotlin.test.Test

internal class AdbExceptionTest {

    @Test
    fun allTypesAreIOExceptions() {
        val exceptions: List<AdbException> = listOf(
            AdbConnectException("x"),
            AdbAuthException("x"),
            AdbStreamOpenException("shell:", "x"),
            AdbConnectionClosedException("x"),
            AdbProtocolException("x"),
        )
        exceptions.forEach { assertThat(it).isInstanceOf(IOException::class.java) }
    }

    @Test
    fun streamOpenExceptionCarriesDestination() {
        val e = AdbStreamOpenException("exec:cmd package install", "refused")
        assertThat(e.destination).isEqualTo("exec:cmd package install")
    }

    @Test
    fun preservesCause() {
        val cause = IOException("socket reset")
        assertThat(AdbConnectException("x", cause).cause).isSameInstanceAs(cause)
    }
}
