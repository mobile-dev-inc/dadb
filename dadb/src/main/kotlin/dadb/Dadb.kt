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

import java.io.File
import java.io.IOException

interface Dadb : AutoCloseable {

    @Throws(IOException::class)
    fun shell(command: String = ""): AdbShellResponse

    @Throws(IOException::class)
    fun openShell(command: String = ""): AdbShellStream

    @Throws(IOException::class)
    fun install(file: File)

    @Throws(IOException::class)
    fun uninstall(packageName: String)

    @Throws(IOException::class)
    fun abbExec(vararg command: String): AdbStream

    @Throws(IOException::class)
    fun root()

    @Throws(IOException::class)
    fun unroot()

    @Throws(IOException::class)
    fun open(destination: String): AdbStream

    companion object {

        fun create(host: String, port: Int, keyPair: AdbKeyPair? = null): Dadb = DadbImpl(host, port, keyPair)
    }
}
