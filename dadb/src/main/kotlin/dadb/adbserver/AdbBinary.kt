package dadb.adbserver

import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.*
import kotlin.system.measureTimeMillis

internal object AdbBinary {

    private val ADB_BINARY: File? by lazy { find() }

    fun tryStartServer(adbServerHost: String, adbServerPort: Int): Boolean {
        return try {
            ensureServerRunning(adbServerHost, adbServerPort)
            true
        } catch (ignore: Exception) {
            false
        }
    }

    fun ensureServerRunning(adbServerHost: String, adbServerPort: Int) {
        if (!isServerRunning(adbServerHost, adbServerPort)) {
            if (adbServerHost != "localhost") {
                throw IOException("No running adb server found at $adbServerHost:$adbServerPort.")
            }
            val adbBinary = ADB_BINARY ?: throw IOException(
                "No running adb server found at $adbServerHost:$adbServerPort and unable to discover an adb binary."
            )
            startServer(adbBinary, adbServerPort)
            // Immediately after starting the adb server, emulators show as offline.
            // This is a hack to work around this behavior.
            Thread.sleep(200)
        }
    }

    private fun startServer(adbBinary: File, adbServerPort: Int) {
        val process = ProcessBuilder(adbBinary.absolutePath, "-P", adbServerPort.toString(), "start-server")
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val output = process.inputStream.bufferedReader().readText()
            throw IOException("Failed to start adb server on port $adbServerPort: $output")
        }
    }

    private fun isServerRunning(adbServerHost: String, adbServerPort: Int): Boolean {
        return try {
            Socket(adbServerHost, adbServerPort).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun find(): File? {
        return findViaWhich() ?: findViaAndroidHome()
    }

    private fun findViaWhich(): File? {
        val which = if (isWindows()) "where" else "which"
        val process = ProcessBuilder(which, "adb").start()
        if (process.waitFor() != 0) return null
        val output = process.inputStream.bufferedReader().use { r ->
            r.readLine().trim()
        }
        val file = File(output)
        if (!file.exists()) return null
        return file
    }

    private fun findViaAndroidHome(): File? {
        val androidEnvHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
        val adbName = if (isWindows()) "adb.exe" else "adb"
        val adbFile = File(androidEnvHome).resolve("platform-tools").resolve(adbName)
        if (!adbFile.exists()) return null
        return adbFile
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name")?.lowercase(Locale.ENGLISH)?.contains("win") == true
    }
}
