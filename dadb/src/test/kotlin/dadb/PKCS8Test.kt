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

import kotlin.test.Test

internal class PKCS8Test {

    private val KEY = """
        -----BEGIN PRIVATE KEY-----
        MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDFEQkuoaxVU887
        bugJRfNr8cOxRtzONcX9dS8XrFxsRqUWnfrwmo0sraMAfWTdRSiknU5yeirIr6Io
        zuVHV0gjRNcQMJtenNZzWVFt9VzkMYVxTH0vJJ1Ik5rVd7BHNXLT7T/ta1MiLimv
        26u5xdJEG5pXGvg767G6koyCnpePx0aIUbtQM49QyyTHuA49Dqqxrz3Zo+rxtdhk
        s+n6iGXK+2OiSV1Va5DL+1EH+s3/RULxeFNZMmOAK29qLwvUf5t4q2X9/ZIP7j4g
        QbTnLzM3P7hQvcLDjIRQZL/J0Nk7XH4gaPQzyvppVav7S3vel0ksbGS/PLvUJiWn
        21KXz/KtAgMBAAECggEAKD8c8XWaVQjbS2eQowgyuSp0jXmL8d9gkq2CkyKj84cQ
        A0j7bXUa/PNvVVPGrDwKG2h3E4EoyLi59PygLcxBEtbl10weBxof4AnvS/Yu5PnK
        J4P4Ew82whJHLm6VxU1AqNCM3EetgE8OO3ixHy0sDrXWdRCwfshZkWGJqcmK6ZVs
        7x7t7tEIPA60Nq8iSncPrlIvBk5OLCZx783Lu30H/t3q9jEuLedfKasD20yn6e6R
        NZgPoB+b9tT7y1bqKo5CC4BWQcEUj1fn6MAqrgmZY3sErDQYJozCIgKTJjlCi+uB
        90rEUKKOYuD+9cq47m1T4OSmWxpsgIJH68G85eTFHwKBgQDhRRONmPMnYYE5iPpH
        OvQTCFrVNA6QV/s5dqlxGfQ1ZSliTCtb2xdblxvkgialgus/s3JQlNRefWkBC1eD
        klnbT8kEWRsspRWIVKFNRDhvy5JC9dP4QFc9uGsEo/z/u2NKJwlPFadMcn8rzYO3
        CIAvGvyVNUiX65YPQNhkp2O4SwKBgQDf8wnAZJ2jPCtXAanWn4mq2QFfo2zkWeRG
        4qWFcpOk7bKGr1mC1sk0OINRvVCVCX8p073I2SJfIHu1RrM9StAyBmRIzABaGv5d
        lxCyzNr7vzb8PcJa1F0wgcGItvsmTKCGU9j4+HtHaw2qNq4ncz+ig1g98T3VQtqn
        ZoLYXxOV5wKBgQCoM1GkOn3j+7PnZ9WodeZkh6p64wG02VylzWo7HuvvKne6A7Gk
        RnSsWKnk9yEwGA7bY3uJm3bujqlmtDdF8HLThEFN09KshR8MylQeQz/4iYHOKYt6
        I2CAn0CZGHEB6cL7TSZwPHTMafl2lV8xvVEo2veZ2U040hkbjomErk+Q/QKBgCbb
        OWbrTjqjVvW6sSgu+CjvjAB3D46zVhtCeeukjJ+CKoaZ6BL+h1yLLaXCDjg9tJWi
        SnyNyBvvO+ehA7pvv53eZAoJc0ovAtFkQ55yUtB5ReYQJSezTxP6f4TkEsF7bCLC
        a5QPMPycQ3u0DxWDNphQ57+fmtXkyqFe9Pbr0C8jAoGAToZRp2GJXbZzkrzFEaSr
        Bg0f0LLef2B+GuYBqJX4UsXhT/m6rK/XFbg5UWb22DJaPKbNg0NaiGn7qovmV0Zk
        BghUTEzffGY1lvQverDFHanGe0wC/bHsfKQdonhn8Q50SZUltjohad+/u+BCx0rW
        P0NjIV7jJ/npgiCgjAXvCxQ=
        -----END PRIVATE KEY-----
    """.trimIndent()

    @Test
    internal fun basic() {
        PKCS8.parse(KEY.toByteArray())
    }
}