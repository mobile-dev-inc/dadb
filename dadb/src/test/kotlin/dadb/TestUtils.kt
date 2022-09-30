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
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

fun assertShellResponse(shellResponse: AdbShellResponse, exitCode: Int, allOutput: String) {
    Truth.assertThat(shellResponse.allOutput).isEqualTo(allOutput)
    Truth.assertThat(shellResponse.exitCode).isEqualTo(exitCode)
}

fun assertShellPacket(shellPacket: AdbShellPacket, packetType: Class<out AdbShellPacket>, payload: String) {
    Truth.assertThat(String(shellPacket.payload)).isEqualTo(payload)
    Truth.assertThat(shellPacket).isInstanceOf(packetType)
}

fun killServer() {
    try {
        // Connection fails if there are simultaneous auth requests
        Runtime.getRuntime().exec("adb kill-server").waitFor()
    } catch (ignore: IOException) {}
}

fun startServer() {
    try {
        Runtime.getRuntime().exec("adb start-server").waitFor()
    } catch (ignore: IOException) {}
}

fun CompletableFuture<*>.waitFor() {
    get(1000, TimeUnit.MILLISECONDS)
}
