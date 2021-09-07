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

internal object Constants {

    const val AUTH_TYPE_TOKEN = 1
    const val AUTH_TYPE_SIGNATURE = 2
    const val AUTH_TYPE_RSA_PUBLIC = 3

    const val CMD_AUTH = 0x48545541
    const val CMD_CNXN = 0x4e584e43
    const val CMD_OPEN = 0x4e45504f
    const val CMD_OKAY = 0x59414b4f
    const val CMD_CLSE = 0x45534c43
    const val CMD_WRTE = 0x45545257

    const val CONNECT_VERSION = 0x01000000
    const val CONNECT_MAXDATA = 1024 * 1024

    val CONNECT_PAYLOAD = "host::\u0000".toByteArray()
}
