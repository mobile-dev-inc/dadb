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

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payloadLength: Int,
        val checksum: Int,
        val magic: Int,
        val payload: ByteArray
) {

    override fun toString() = "${commandStr()}[${argStr(arg0)}, ${argStr(arg1)}] ${payloadStr()}"

    private fun payloadStr(): String {
        if (payloadLength == 0) return ""
        return when (command) {
            Constants.CMD_AUTH -> if (arg0 == Constants.AUTH_TYPE_RSA_PUBLIC) String(payload) else "auth[${payloadLength}]"
            Constants.CMD_WRTE -> writePayloadStr()
            Constants.CMD_OPEN -> String(payload, 0, payloadLength - 1)
            else -> "payload[$payloadLength]"
        }
    }

    private fun writePayloadStr(): String {
        return shellWritePayloadStr()?.let { "[shell] $it" } ?: "payload[$payloadLength]"
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun shellWritePayloadStr(): String? {
        val buffer = ByteBuffer.wrap(payload, 0, payloadLength).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.limit() < 8) return null
        val id = buffer.get().toInt()
        if (id < 0 || id > 3) return null
        val length = buffer.getInt()
        if (length != buffer.remaining()) return null
        if (id == ID_EXIT) return "EXIT[${buffer.get()}]"
        return String(payload, 5, payloadLength - 5)
    }

    private fun argStr(arg: Int) = String.format("%X", arg)

    private fun commandStr() = when (command) {
        Constants.CMD_AUTH -> "AUTH";
        Constants.CMD_CNXN -> "CNXN";
        Constants.CMD_OPEN -> "OPEN";
        Constants.CMD_OKAY -> "OKAY";
        Constants.CMD_CLSE -> "CLSE";
        Constants.CMD_WRTE -> "WRTE";
        else -> "????"
    }
}
