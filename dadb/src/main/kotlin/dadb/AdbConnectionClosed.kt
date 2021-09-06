package dadb

import java.lang.RuntimeException

internal class AdbConnectionClosed(localId: Int) : RuntimeException(String.format("ADB connection is closed for localId: %x", localId))