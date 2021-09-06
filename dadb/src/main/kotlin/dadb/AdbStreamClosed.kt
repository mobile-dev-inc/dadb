package dadb

import java.lang.RuntimeException

internal class AdbStreamClosed(localId: Int) : RuntimeException(String.format("ADB stream is closed for localId: %x", localId))