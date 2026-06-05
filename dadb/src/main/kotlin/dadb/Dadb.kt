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

import dadb.adbserver.AdbServer
import dadb.forwarding.TcpForwarder
import java.io.File
import java.nio.file.Files
import okio.*

interface Dadb : AutoCloseable {

    @Throws(AdbException::class)
    fun open(destination: String): AdbStream

    fun supportsFeature(feature: String): Boolean

    @Throws(AdbException::class)
    fun shell(command: String): AdbShellResponse {
        openShell(command).use { stream ->
            return stream.readAll()
        }
    }

    @Throws(AdbException::class)
    fun openShell(command: String = ""): AdbShellStream {
        val stream = open("shell,v2,raw:$command")
        return AdbShellStream(stream)
    }

    @Throws(AdbException::class)
    fun push(src: File, remotePath: String, mode: Int = readMode(src), lastModifiedMs: Long = src.lastModified()): SyncResult {
        return push(src.source(), remotePath, mode, lastModifiedMs)
    }

    @Throws(AdbException::class)
    fun push(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long): SyncResult {
        openSync().use { stream ->
            return try {
                stream.send(source, remotePath, mode, lastModifiedMs)
                SyncResult.Success
            } catch (e: AdbSyncFailException) {
                SyncResult.Failure(e.reason)
            }
        }
    }

    @Throws(AdbException::class)
    fun pull(dst: File, remotePath: String): SyncResult {
        return pull(dst.sink(append = false), remotePath)
    }

    @Throws(AdbException::class)
    fun pull(sink: Sink, remotePath: String): SyncResult {
        openSync().use { stream ->
            return try {
                stream.recv(sink, remotePath)
                SyncResult.Success
            } catch (e: AdbSyncFailException) {
                SyncResult.Failure(e.reason)
            }
        }
    }

    @Throws(AdbException::class)
    fun openSync(): AdbSyncStream {
        val stream = open("sync:")
        return AdbSyncStream(stream)
    }

    @Throws(AdbException::class)
    fun install(file: File, vararg options: String): InstallResult {
        return if (supportsFeature("cmd")) {
            install(file.source(), file.length(), *options)
        } else {
            pmInstall(file, *options)
        }
    }

    @Throws(AdbException::class)
    fun install(source: Source, size: Long, vararg options: String): InstallResult {
        if (supportsFeature("cmd")) {
            execCmd("package", "install", "-S", size.toString(), *options).use { stream ->
                stream.sink.writeAll(source)
                stream.sink.flush()
                val response = stream.source.readString(Charsets.UTF_8)
                return if (response.startsWith("Success")) InstallResult.Success else InstallResult.Failure(response)
            }
        } else {
            val tempFile = kotlin.io.path.createTempFile()
            val fileSink = tempFile.sink().buffer()
            fileSink.writeAll(source)
            fileSink.flush()
            return pmInstall(tempFile.toFile(), *options)
        }
    }

    private fun pmInstall(file: File, vararg options: String): InstallResult {
        val remotePath = "/data/local/tmp/${file.name}"
        when (val pushResult = push(file, remotePath)) {
            is SyncResult.Failure -> return InstallResult.Failure("push failed: ${pushResult.reason}")
            SyncResult.Success -> Unit
        }
        val response = shell("pm install ${options.joinToString(" ")} \"$remotePath\"")
        return if (response.allOutput.startsWith("Success")) InstallResult.Success else InstallResult.Failure(response.allOutput)
    }

    @Throws(AdbException::class)
    fun installMultiple(apks: List<File>, vararg options: String): InstallResult {
        // http://aospxref.com/android-12.0.0_r3/xref/packages/modules/adb/client/adb_install.cpp#538
        if (supportsFeature("cmd")) {
            val totalLength = apks.map { it.length() }.reduce { acc, l -> acc + l }
            execCmd("package", "install-create", "-S", totalLength.toString(), *options).use { createStream ->
                val response = createStream.source.readString(Charsets.UTF_8)
                if (!response.startsWith("Success")) return InstallResult.Failure("create session failed: $response")

                val pattern = """\[(\w+)]""".toRegex()
                val sessionId = pattern.find(response)?.groups?.get(1)?.value
                    ?: return InstallResult.Failure("failed to parse session id: $response")

                var error: String? = null
                apks.forEach { apk ->
                    execCmd("package", "install-write", "-S", apk.length().toString(), sessionId, apk.name, "-", *options).use { writeStream ->
                        writeStream.sink.writeAll(apk.source())
                        writeStream.sink.flush()
                        val writeResponse = writeStream.source.readString(Charsets.UTF_8)
                        if (!writeResponse.startsWith("Success")) {
                            error = writeResponse
                            return@forEach
                        }
                    }
                }

                val finalCommand = if (error == null) "install-commit" else "install-abandon"
                execCmd("package", finalCommand, sessionId, *options).use { commitStream ->
                    val finalResponse = commitStream.source.readString(Charsets.UTF_8)
                    if (!finalResponse.startsWith("Success")) return InstallResult.Failure("failed to finalize session: $finalResponse")
                }

                return if (error == null) InstallResult.Success else InstallResult.Failure(error!!)
            }
        } else {
            val totalLength = apks.map { it.length() }.reduce { acc, l -> acc + l }
            val response = shell("pm install-create -S $totalLength ${options.joinToString(" ")}")
            if (!response.allOutput.startsWith("Success")) return InstallResult.Failure("pm create session failed: ${response.allOutput}")

            val pattern = """\[(\w+)]""".toRegex()
            val sessionId = pattern.find(response.allOutput)?.groups?.get(1)?.value
                ?: return InstallResult.Failure("failed to parse session id: ${response.allOutput}")
            var error: String? = null

            val fileNames = apks.map { it.name }
            val remotePaths = fileNames.map { "/data/local/tmp/$it" }

            apks.zip(remotePaths).forEachIndexed { index, pair ->
                val apk = pair.first
                val remotePath = pair.second

                when (val pushResult = push(apk, remotePath)) {
                    is SyncResult.Failure -> { error = "push failed: ${pushResult.reason}"; return@forEachIndexed }
                    SyncResult.Success -> Unit
                }

                val writeResponse = shell("pm install-write -S ${apk.length()} $sessionId $index $remotePath")
                if (!writeResponse.allOutput.startsWith("Success")) {
                    error = writeResponse.allOutput
                    return@forEachIndexed
                }
            }

            val finalCommand = if (error == null) "pm install-commit $sessionId" else "pm install-abandon $sessionId"
            val finalResponse = shell(finalCommand)
            if (!finalResponse.allOutput.startsWith("Success")) return InstallResult.Failure("failed to finalize session: ${finalResponse.allOutput}")
            return if (error == null) InstallResult.Success else InstallResult.Failure(error!!)
        }
    }

    @Throws(AdbException::class)
    fun uninstall(packageName: String): UninstallResult {
        val response = shell("cmd package uninstall $packageName")
        return if (response.exitCode == 0) {
            UninstallResult.Success
        } else {
            UninstallResult.Failure(reason = response.allOutput, exitCode = response.exitCode)
        }
    }

    @Throws(AdbException::class)
    fun execCmd(vararg command: String): AdbStream {
        if (!supportsFeature("cmd")) throw UnsupportedOperationException("cmd is not supported on this version of Android")
        val destination = (listOf("exec:cmd") + command).joinToString(" ")
        return open(destination)
    }

    @Throws(AdbException::class)
    fun abbExec(vararg command: String): AdbStream {
        if (!supportsFeature("abb_exec")) throw UnsupportedOperationException("abb_exec is not supported on this version of Android")
        val destination = "abb_exec:${command.joinToString("\u0000")}"
        return open(destination)
    }

    @Throws(AdbException::class)
    fun root(): RootResult = restartAdbd("root:", root = true) { it.startsWith("restarting") || it.contains("already") }

    @Throws(AdbException::class)
    fun unroot(): RootResult = restartAdbd("unroot:", root = false) { it.startsWith("restarting") || it.contains("not running as root") }

    private fun restartAdbd(service: String, root: Boolean, isSuccess: (String) -> Boolean): RootResult {
        val response = restartAdb(this, service)
        if (!isSuccess(response)) return RootResult.Failure(response)
        waitRootOrClose(this, root)
        return RootResult.Success
    }

    @Throws(InterruptedException::class)
    fun tcpForward(hostPort: Int, targetPort: Int): AutoCloseable {
        val forwarder = TcpForwarder(this, hostPort, targetPort)
        forwarder.start()

        return forwarder
    }

    companion object {

        private const val MIN_EMULATOR_PORT = 5555
        private const val MAX_EMULATOR_PORT = 5683

        @JvmStatic
        @JvmOverloads
        fun create(host: String, port: Int, keyPair: AdbKeyPair? = AdbKeyPair.readDefault(), connectTimeout: Int = 0, socketTimeout: Int = 0, keepAlive: Boolean = false): Dadb = DadbImpl(host, port, keyPair, connectTimeout, socketTimeout, keepAlive = keepAlive)

        @JvmStatic
        @JvmOverloads
        fun discover(host: String = "localhost", keyPair: AdbKeyPair? = AdbKeyPair.readDefault(), connectTimeout: Int = 0, socketTimeout: Int = 0, keepAlive: Boolean = false): Dadb? {
            return list(host, keyPair, connectTimeout, socketTimeout, keepAlive).firstOrNull()
        }

        @JvmStatic
        @JvmOverloads
        fun list(host: String = "localhost", keyPair: AdbKeyPair? = AdbKeyPair.readDefault(), connectTimeout: Int = 0, socketTimeout: Int = 0, keepAlive: Boolean = false): List<Dadb> {
            val dadbs = AdbServer.listDadbs(adbServerHost = host)
            if (dadbs.isNotEmpty()) return dadbs

            return (MIN_EMULATOR_PORT .. MAX_EMULATOR_PORT).mapNotNull { port ->
                val dadb = create(host, port, keyPair, socketTimeout, connectTimeout, keepAlive = keepAlive)
                val response = try {
                    dadb.shell("echo success").allOutput
                } catch (ignore : Throwable) {
                    null
                }
                if (response == "success\n") {
                    dadb
                } else {
                    null
                }
            }
        }

        private fun waitRootOrClose(dadb: Dadb, root: Boolean) {
            while (true) {
                try {
                    val response = dadb.shell("getprop service.adb.root")
                    val propValue = if (root) 1 else 0
                    if (response.output == "$propValue\n") return
                } catch (e: IOException) {
                    return
                }
            }
        }

        private fun restartAdb(dadb: Dadb, destination: String): String {
            dadb.open(destination).use { stream ->
                return stream.source.readUntil('\n'.toByte()).readString(Charsets.UTF_8)
            }
        }

        private fun BufferedSource.readUntil(endByte: Byte): Buffer {
            val buffer = Buffer()
            while (true) {
                val b = readByte()
                buffer.writeByte(b.toInt())
                if (b == endByte) return buffer
            }
        }

        private fun readMode(file: File): Int {
            return Files.getAttribute(file.toPath(), "unix:mode") as? Int ?: throw RuntimeException("Unable to read file mode")
        }
    }
}
