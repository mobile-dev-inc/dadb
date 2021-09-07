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

import org.junit.After
import kotlin.test.Test

internal class DadbTest {

    private val dadb = Dadb.create("localhost", 5555) as DadbImpl

    @After
    fun tearDown() {
        dadb.close()
    }

    @Test
    fun reconnection() {
        assertShellResponse(dadb.shell("echo hello1"), 0, "hello1\n")

        dadb.closeConnection()

        assertShellResponse(dadb.shell("echo hello2"), 0, "hello2\n")
    }

    @Test
    fun root() {
        dadb.root()
        dadb.unroot()
    }
}
