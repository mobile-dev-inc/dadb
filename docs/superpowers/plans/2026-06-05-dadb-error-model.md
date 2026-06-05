# dadb Error Model Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace dadb's undifferentiated `IOException` error model with an adbd-aware contract — typed transport exceptions (`AdbException` hierarchy) for connection failures, and operation-specific result types (`InstallResult`/`UninstallResult`/`SyncResult`/`RootResult`) for in-band outcomes.

**Architecture:** A sealed `AdbException : IOException` hierarchy carries every transport/auth/protocol failure (one type per documented adbd `protocol.txt` failure point). Expected operation outcomes become return values; the named high-level `Dadb` methods return per-operation sealed result types. Internal stream codecs throw a private `AdbSyncFailException` for sync `FAIL`, which the high-level methods catch and fold into a result. See the design spec: `docs/superpowers/specs/2026-06-05-dadb-error-model-design.md`.

**Tech Stack:** Kotlin/JVM, okio 2.10.0, JUnit Platform (`kotlin.test`), Google Truth. Unit tests drive the wire protocol with in-memory okio `Buffer`s (the existing `AdbStreamTest` pattern); a `FakeDadb`/`FakeAdbStream` fixture exercises the high-level `Dadb` default methods without an emulator.

**Scope:** Direct-to-adbd error handling only. Out of scope (do NOT touch): the `dadb.adbserver.*` host-server path, `discover`/`list` restructuring, the swapped-timeout bug at `Dadb.kt:271`, and the concurrency/resource fixes noted in review.

**Conventions for every task:** new files get the standard Apache-2.0 license header (copy it verbatim from any existing file in `dadb/src/main/kotlin/dadb/`). Run the build from the repo root with `./gradlew :dadb:test`. Compile-only checks use `./gradlew :dadb:compileKotlin :dadb:compileTestKotlin`.

---

## File Structure

**New main files:**
- `dadb/src/main/kotlin/dadb/AdbException.kt` — sealed `AdbException` + the five transport subtypes.
- `dadb/src/main/kotlin/dadb/InstallResult.kt`, `UninstallResult.kt`, `SyncResult.kt`, `RootResult.kt` — operation result types.

**New test files:**
- `dadb/src/test/kotlin/dadb/FakeDadb.kt` — `FakeAdbStream`, `FakeDadb`, and the `shellV2Buffer`/`syncFailBuffer` byte-builders.
- `dadb/src/test/kotlin/dadb/AdbExceptionTest.kt`, `ResultTypesTest.kt`, `AdbConnectionHandshakeTest.kt`, `DadbResultTest.kt` — unit tests.

**Modified main files:**
- `dadb/src/main/kotlin/dadb/AdbStream.kt` — `nextMessage` distinguishes clean close from socket fault.
- `dadb/src/main/kotlin/dadb/AdbConnection.kt` — handshake + `open` map failures to typed exceptions; one `private`→`internal` test seam.
- `dadb/src/main/kotlin/dadb/AdbSync.kt` — internal `AdbSyncFailException`; `send`/`recv` throw it on `FAIL`.
- `dadb/src/main/kotlin/dadb/Dadb.kt` — high-level methods return result types; `@Throws(AdbException::class)`.

**Modified test files:**
- `dadb/src/test/kotlin/dadb/AdbStreamTest.kt` — add the `nextMessage` behavior tests.
- `dadb/src/test/kotlin/dadb/DadbTest.kt` — update emulator tests to the new return types; add `AdbStreamOpenException` test.

---

## Task 1: `AdbException` hierarchy

**Files:**
- Create: `dadb/src/main/kotlin/dadb/AdbException.kt`
- Test: `dadb/src/test/kotlin/dadb/AdbExceptionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `dadb/src/test/kotlin/dadb/AdbExceptionTest.kt`:

```kotlin
package dadb

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlin.test.Test

internal class AdbExceptionTest {

    @Test
    fun allTypesAreIOExceptions() {
        val exceptions: List<AdbException> = listOf(
            AdbConnectException("x"),
            AdbAuthException("x"),
            AdbStreamOpenException("shell:", "x"),
            AdbConnectionClosedException("x"),
            AdbProtocolException("x"),
        )
        exceptions.forEach { assertThat(it).isInstanceOf(IOException::class.java) }
    }

    @Test
    fun streamOpenExceptionCarriesDestination() {
        val e = AdbStreamOpenException("exec:cmd package install", "refused")
        assertThat(e.destination).isEqualTo("exec:cmd package install")
    }

    @Test
    fun preservesCause() {
        val cause = IOException("socket reset")
        assertThat(AdbConnectException("x", cause).cause).isSameInstanceAs(cause)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :dadb:compileTestKotlin`
Expected: FAIL — unresolved references `AdbConnectException`, `AdbException`, etc.

- [ ] **Step 3: Write the implementation**

Create `dadb/src/main/kotlin/dadb/AdbException.kt` (prefix with the Apache-2.0 license header copied from an existing file):

```kotlin
package dadb

import java.io.IOException

/**
 * Root of all dadb transport/connection failures. Extends [IOException] because these are genuine,
 * recoverable socket I/O faults, consistent with the okio stream surface dadb exposes. Every
 * transport fault thrown from a public method is one of these subtypes — no bare IOException leaks.
 */
sealed class AdbException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Could not establish the connection: TCP connect/timeout, or the A_CNXN handshake failed.
 *  Nothing ran — safe to retry by reconnecting. */
class AdbConnectException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** A_AUTH was rejected (no key, or device left UNAUTHORIZED). Reconnecting with the same key will
 *  not help — fix or accept the key. */
class AdbAuthException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** adbd refused to OPEN the stream (A_OPEN answered with A_CLSE): unknown/forbidden service string,
 *  or device offline. The underlying connection is still usable. */
class AdbStreamOpenException(
    val destination: String,
    message: String,
    cause: Throwable? = null
) : AdbException(message, cause)

/** An established connection/stream died unexpectedly (socket EOF/RST mid-operation). Reconnect —
 *  but the operation MAY have partially executed; re-check state before retrying non-idempotent work. */
class AdbConnectionClosedException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** The peer sent a malformed or unexpected apacket (desync). No reliable way to re-sync — the
 *  connection must be closed and re-established. */
class AdbProtocolException(message: String, cause: Throwable? = null) : AdbException(message, cause)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :dadb:test --tests dadb.AdbExceptionTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add dadb/src/main/kotlin/dadb/AdbException.kt dadb/src/test/kotlin/dadb/AdbExceptionTest.kt
git commit -m "feat: add AdbException transport exception hierarchy"
```

---

## Task 2: Operation result types

**Files:**
- Create: `dadb/src/main/kotlin/dadb/InstallResult.kt`, `UninstallResult.kt`, `SyncResult.kt`, `RootResult.kt`
- Test: `dadb/src/test/kotlin/dadb/ResultTypesTest.kt`

- [ ] **Step 1: Write the failing test**

Create `dadb/src/test/kotlin/dadb/ResultTypesTest.kt`:

```kotlin
package dadb

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

internal class ResultTypesTest {

    @Test
    fun installResultVariants() {
        val ok: InstallResult = InstallResult.Success
        val fail: InstallResult = InstallResult.Failure("Failure [INSTALL_FAILED_INVALID_APK]")
        assertThat(ok).isInstanceOf(InstallResult.Success::class.java)
        assertThat((fail as InstallResult.Failure).reason).contains("INSTALL_FAILED")
    }

    @Test
    fun uninstallFailureCarriesExitCodeAndReason() {
        val fail = UninstallResult.Failure(reason = "Failure [DELETE_FAILED_INTERNAL_ERROR]", exitCode = 1)
        assertThat(fail.exitCode).isEqualTo(1)
        assertThat(fail.reason).contains("DELETE_FAILED")
    }

    @Test
    fun syncAndRootVariants() {
        assertThat(SyncResult.Failure("No such file or directory").reason).contains("No such file")
        assertThat(RootResult.Failure("adbd cannot run as root in production builds").reason)
            .contains("production builds")
        val syncOk: SyncResult = SyncResult.Success
        val rootOk: RootResult = RootResult.Success
        assertThat(syncOk).isInstanceOf(SyncResult.Success::class.java)
        assertThat(rootOk).isInstanceOf(RootResult.Success::class.java)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :dadb:compileTestKotlin`
Expected: FAIL — unresolved `InstallResult`, `UninstallResult`, `SyncResult`, `RootResult`.

- [ ] **Step 3: Write the implementations**

Create `dadb/src/main/kotlin/dadb/InstallResult.kt` (with license header):

```kotlin
package dadb

/** Outcome of [Dadb.install] / [Dadb.installMultiple]. */
sealed interface InstallResult {
    object Success : InstallResult
    /** Raw pm/`cmd package` verdict, surfaced opaquely — exactly as the canonical adb client does
     *  (client/adb_install.cpp checks `strncmp("Success", buf, 7)` and prints the rest verbatim).
     *  `INSTALL_FAILED_*` codes are a frameworks/base concept, not the adbd contract, so they are
     *  not parsed into an enum. */
    data class Failure(val reason: String) : InstallResult
}
```

Create `dadb/src/main/kotlin/dadb/UninstallResult.kt` (with license header):

```kotlin
package dadb

/** Outcome of [Dadb.uninstall]. `uninstall` is `cmd package uninstall` over the shell service, so
 *  [Failure] preserves adbd's actual process exit code and combined output — the wrapper hides nothing. */
sealed interface UninstallResult {
    object Success : UninstallResult
    data class Failure(val reason: String, val exitCode: Int) : UninstallResult
}
```

Create `dadb/src/main/kotlin/dadb/SyncResult.kt` (with license header):

```kotlin
package dadb

/** Outcome of [Dadb.push] / [Dadb.pull]. [Failure.reason] is the adbd sync FAIL message. */
sealed interface SyncResult {
    object Success : SyncResult
    data class Failure(val reason: String) : SyncResult
}
```

Create `dadb/src/main/kotlin/dadb/RootResult.kt` (with license header):

```kotlin
package dadb

/** Outcome of [Dadb.root] / [Dadb.unroot]. [Failure.reason] is the adbd root:/unroot: response line. */
sealed interface RootResult {
    object Success : RootResult
    data class Failure(val reason: String) : RootResult
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :dadb:test --tests dadb.ResultTypesTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add dadb/src/main/kotlin/dadb/InstallResult.kt dadb/src/main/kotlin/dadb/UninstallResult.kt dadb/src/main/kotlin/dadb/SyncResult.kt dadb/src/main/kotlin/dadb/RootResult.kt dadb/src/test/kotlin/dadb/ResultTypesTest.kt
git commit -m "feat: add operation result types"
```

---

## Task 3: Test fixtures (`FakeDadb`, byte-builders)

This is test scaffolding (no red/green of its own); later tasks depend on it. It must compile against the `Dadb` interface and existing wire constants.

**Files:**
- Create: `dadb/src/test/kotlin/dadb/FakeDadb.kt`

- [ ] **Step 1: Write the fixture**

Create `dadb/src/test/kotlin/dadb/FakeDadb.kt`:

```kotlin
package dadb

import okio.Buffer
import java.nio.charset.StandardCharsets

/** An [AdbStream] backed by in-memory buffers. [source] is pre-loaded with device bytes;
 *  [sink] captures whatever the code under test writes. */
internal class FakeAdbStream(
    override val source: Buffer,
    override val sink: Buffer = Buffer()
) : AdbStream {
    override fun close() {}
}

/** A [Dadb] whose `open(destination)` returns a caller-supplied [AdbStream]. Records every
 *  destination opened, in order, in [opened]. */
internal class FakeDadb(
    private val features: Set<String> = setOf("shell_v2", "cmd"),
    private val streamFor: (destination: String) -> AdbStream
) : Dadb {
    val opened = mutableListOf<String>()

    override fun open(destination: String): AdbStream {
        opened += destination
        return streamFor(destination)
    }

    override fun supportsFeature(feature: String) = feature in features

    override fun close() {}
}

/** Builds a shell,v2 byte stream: optional stdout/stderr packets followed by an exit packet. */
internal fun shellV2Buffer(stdout: String = "", stderr: String = "", exitCode: Int): Buffer {
    val buffer = Buffer()
    if (stdout.isNotEmpty()) {
        val bytes = stdout.toByteArray()
        buffer.writeByte(ID_STDOUT); buffer.writeIntLe(bytes.size); buffer.write(bytes)
    }
    if (stderr.isNotEmpty()) {
        val bytes = stderr.toByteArray()
        buffer.writeByte(ID_STDERR); buffer.writeIntLe(bytes.size); buffer.write(bytes)
    }
    buffer.writeByte(ID_EXIT); buffer.writeIntLe(1); buffer.writeByte(exitCode)
    return buffer
}

/** Builds a single sync `FAIL` packet (4-char id + LE length + message). */
internal fun syncFailBuffer(message: String): Buffer {
    val buffer = Buffer()
    buffer.writeString("FAIL", StandardCharsets.UTF_8)
    buffer.writeIntLe(message.toByteArray().size)
    buffer.writeString(message, StandardCharsets.UTF_8)
    return buffer
}

/** Builds a single sync `OKAY` packet (success terminator for SEND). */
internal fun syncOkayBuffer(): Buffer {
    val buffer = Buffer()
    buffer.writeString("OKAY", StandardCharsets.UTF_8)
    buffer.writeIntLe(0)
    return buffer
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :dadb:compileTestKotlin`
Expected: PASS (compiles; `FakeAdbStream` satisfies `AdbStream`, constants `ID_STDOUT`/`ID_STDERR`/`ID_EXIT` resolve from `AdbShell.kt`).

- [ ] **Step 3: Commit**

```bash
git add dadb/src/test/kotlin/dadb/FakeDadb.kt
git commit -m "test: add FakeDadb fixture and wire byte-builders"
```

---

## Task 4: `AdbStream.nextMessage` — clean close vs socket fault

The core behavioral fix: a peer `A_CLSE` is a legitimate EOF; a real socket fault must surface as `AdbConnectionClosedException` instead of masquerading as a clean end-of-stream.

**Files:**
- Modify: `dadb/src/main/kotlin/dadb/AdbStream.kt:117-124`
- Test: `dadb/src/test/kotlin/dadb/AdbStreamTest.kt` (add tests)

- [ ] **Step 1: Write the failing tests**

Append these two methods inside the `AdbStreamTest` class in `dadb/src/test/kotlin/dadb/AdbStreamTest.kt` (and add the imports `import kotlin.test.assertFailsWith` and `import java.io.IOException` at the top):

```kotlin
    @Test
    fun peerCloseIsCleanEof() {
        // Reader yields a CLSE for localId=1 and nothing else.
        val buffer = Buffer()
        AdbWriter(buffer).write(Constants.CMD_CLSE, 2, 1, null, 0, 0)
        val messageQueue = AdbMessageQueue(AdbReader(buffer))
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(messageQueue, AdbWriter(Buffer()), 1024, 1, 2)

        // A clean peer close reads as EOF (empty), NOT an exception.
        Truth.assertThat(stream.source.readByteArray()).isEqualTo(ByteArray(0))
    }

    @Test
    fun socketFaultThrowsConnectionClosed() {
        // Empty reader: the next read hits EOF on the socket itself (not a protocol CLSE).
        val messageQueue = AdbMessageQueue(AdbReader(Buffer()))
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(messageQueue, AdbWriter(Buffer()), 1024, 1, 2)

        assertFailsWith<AdbConnectionClosedException> { stream.source.readByteArray() }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :dadb:test --tests dadb.AdbStreamTest`
Expected: `peerCloseIsCleanEof` PASSES already (today's swallow returns EOF), `socketFaultThrowsConnectionClosed` FAILS (today's swallow also returns EOF, so `readByteArray()` returns empty instead of throwing).

- [ ] **Step 3: Apply the fix**

In `dadb/src/main/kotlin/dadb/AdbStream.kt`, replace the `nextMessage` method (lines 117-124):

```kotlin
    private fun nextMessage(command: Int): AdbMessage? {
        return try {
            messageQueue.take(localId, command)
        } catch (e: IOException) {
            close()
            return null
        }
    }
```

with:

```kotlin
    private fun nextMessage(command: Int): AdbMessage? {
        return try {
            messageQueue.take(localId, command)
        } catch (e: AdbStreamClosed) {
            // Peer sent A_CLSE: normal end-of-stream (e.g. shell/exec output finished). EOF is correct.
            close()
            null
        } catch (e: IOException) {
            // Real socket fault (EOF/RST) mid-stream: the transport died, not a clean close.
            close()
            throw AdbConnectionClosedException("Connection lost while reading stream $localId", e)
        }
    }
```

(`AdbStream.kt` already imports `java.io.IOException`; `AdbStreamClosed` and `AdbConnectionClosedException` are in the same package — no new imports needed.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :dadb:test --tests dadb.AdbStreamTest`
Expected: PASS (3 tests, including the original `testLargeRemoteWrite`)

- [ ] **Step 5: Commit**

```bash
git add dadb/src/main/kotlin/dadb/AdbStream.kt dadb/src/test/kotlin/dadb/AdbStreamTest.kt
git commit -m "fix: surface mid-stream socket faults as AdbConnectionClosedException"
```

---

## Task 5: `AdbConnection` handshake — typed failures

Map handshake failures to typed exceptions and guarantee no bare `IOException` leaks from the handshake. Requires making the `(Source, Sink)` connect overload `internal` as a test seam.

**Files:**
- Modify: `dadb/src/main/kotlin/dadb/AdbConnection.kt:86-137`
- Test: `dadb/src/test/kotlin/dadb/AdbConnectionHandshakeTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `dadb/src/test/kotlin/dadb/AdbConnectionHandshakeTest.kt`:

```kotlin
package dadb

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class AdbConnectionHandshakeTest {

    @Test
    fun nonCnxnResponseThrowsConnectException() {
        // Device replies with an unexpected OKAY instead of CNXN.
        val device = Buffer()
        AdbWriter(device).writeOkay(0, 0)
        assertFailsWith<AdbConnectException> {
            AdbConnection.connect(device, Buffer(), null)
        }
    }

    @Test
    fun authRequiredWithoutKeyPairThrowsAuthException() {
        // Device demands auth; we have no key pair.
        val device = Buffer()
        AdbWriter(device).writeAuth(Constants.AUTH_TYPE_TOKEN, ByteArray(20))
        assertFailsWith<AdbAuthException> {
            AdbConnection.connect(device, Buffer(), null)
        }
    }

    @Test
    fun truncatedHandshakeThrowsConnectException() {
        // Empty device stream: the first readMessage hits EOF.
        assertFailsWith<AdbConnectException> {
            AdbConnection.connect(Buffer(), Buffer(), null)
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :dadb:compileTestKotlin`
Expected: FAIL — `connect(Source, Sink, ...)` is `private` (unresolved/inaccessible).

- [ ] **Step 3: Make the test seam internal and map failures**

In `dadb/src/main/kotlin/dadb/AdbConnection.kt`:

(a) Change the visibility of the `(Source, Sink)` overload (line 86) from `private` to `internal`, and wrap its body so any non-`AdbException` `IOException` from the handshake becomes `AdbConnectException`:

```kotlin
        internal fun connect(source: Source, sink: Sink, keyPair: AdbKeyPair? = null, closeable: Closeable? = null): AdbConnection {
            val adbReader = AdbReader(source)
            val adbWriter = AdbWriter(sink)

            try {
                return connect(adbReader, adbWriter, keyPair, closeable)
            } catch (e: AdbException) {
                adbReader.close()
                adbWriter.close()
                throw e
            } catch (e: IOException) {
                adbReader.close()
                adbWriter.close()
                throw AdbConnectException("Connection handshake failed", e)
            } catch (t: Throwable) {
                adbReader.close()
                adbWriter.close()
                throw t
            }
        }
```

(b) Replace the handshake body in the `(AdbReader, AdbWriter)` overload (lines 99-125) with typed mappings:

```kotlin
        private fun connect(adbReader: AdbReader, adbWriter: AdbWriter, keyPair: AdbKeyPair?, closeable: Closeable?): AdbConnection {
            adbWriter.writeConnect()

            var message = adbReader.readMessage()

            if (message.command == Constants.CMD_AUTH) {
                if (keyPair == null) throw AdbAuthException("Authentication required but no key pair was provided")
                if (message.arg0 != Constants.AUTH_TYPE_TOKEN) throw AdbProtocolException("Unsupported auth type: $message")

                val signature = keyPair.signPayload(message)
                adbWriter.writeAuth(Constants.AUTH_TYPE_SIGNATURE, signature)

                message = adbReader.readMessage()
                if (message.command == Constants.CMD_AUTH) {
                    adbWriter.writeAuth(Constants.AUTH_TYPE_RSA_PUBLIC, keyPair.publicKeyBytes)
                    message = adbReader.readMessage()
                }
            }

            if (message.command != Constants.CMD_CNXN) {
                // A trailing AUTH means the device rejected our key / stayed unauthorized.
                if (message.command == Constants.CMD_AUTH) throw AdbAuthException("Device rejected authentication (unauthorized)")
                throw AdbConnectException("Connection failed: $message")
            }

            val connectionString = parseConnectionString(String(message.payload))
            val version = message.arg0
            val maxPayloadSize = message.arg1

            return AdbConnection(adbReader, adbWriter, closeable, connectionString.features, version, maxPayloadSize)
        }
```

(c) In `parseConnectionString` (line 134), change the feature-parse throw to a connect failure:

```kotlin
            if ("features" !in keyValues) throw AdbConnectException("Failed to parse features from connection string: $connectionString")
```

(`AdbConnection.kt` already imports `java.io.IOException`; the new exception types share the package.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :dadb:test --tests dadb.AdbConnectionHandshakeTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add dadb/src/main/kotlin/dadb/AdbConnection.kt dadb/src/test/kotlin/dadb/AdbConnectionHandshakeTest.kt
git commit -m "feat: map adbd handshake failures to typed AdbException subtypes"
```

---

## Task 6: `AdbConnection.open` — stream-open refusal

When adbd answers `A_OPEN` with `A_CLSE`, surface `AdbStreamOpenException` (the connection is still alive) instead of the internal `AdbStreamClosed`. The `localId` is random, so this mapping is verified by an emulator test (opening a bogus service) plus the implementation review.

**Files:**
- Modify: `dadb/src/main/kotlin/dadb/AdbConnection.kt:42-55`
- Test: `dadb/src/test/kotlin/dadb/DadbTest.kt` (add one emulator test)

- [ ] **Step 1: Write the failing emulator test**

Add this method inside the `DadbTest` class in `dadb/src/test/kotlin/dadb/DadbTest.kt` (it requires a running emulator on `localhost:5555`, like the other `localEmulator { }` tests; add `import kotlin.test.assertFailsWith` if not present):

```kotlin
    @Test
    fun open_invalidService_throwsStreamOpenException() {
        localEmulator { dadb ->
            assertFailsWith<AdbStreamOpenException> {
                dadb.open("definitely-not-a-real-service:")
            }
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :dadb:test --tests dadb.DadbTestImpl`
Expected: FAIL — today this throws the internal `AdbStreamClosed` (an `IOException`), not `AdbStreamOpenException`. (If no emulator is available the test errors on connect; in that case verify the mapping by code review and proceed.)

- [ ] **Step 3: Apply the mapping**

In `dadb/src/main/kotlin/dadb/AdbConnection.kt`, replace the `open` method (lines 42-55):

```kotlin
    @Throws(IOException::class)
    fun open(destination: String): AdbStream {
        val localId = newId()
        messageQueue.startListening(localId)
        try {
            adbWriter.writeOpen(localId, destination)
            val message = messageQueue.take(localId, Constants.CMD_OKAY)
            val remoteId = message.arg0
            return AdbStreamImpl(messageQueue, adbWriter, maxPayloadSize, localId, remoteId)
        } catch (e: AdbStreamClosed) {
            // adbd answered A_OPEN with A_CLSE: it refused this service. Connection is still alive.
            messageQueue.stopListening(localId)
            throw AdbStreamOpenException(destination, "adbd refused to open stream: $destination", e)
        } catch (e: Throwable) {
            messageQueue.stopListening(localId)
            throw e
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :dadb:test --tests dadb.DadbTestImpl`
Expected: PASS (with emulator). Without an emulator, run `./gradlew :dadb:compileKotlin` and confirm it compiles, then proceed.

- [ ] **Step 5: Commit**

```bash
git add dadb/src/main/kotlin/dadb/AdbConnection.kt dadb/src/test/kotlin/dadb/DadbTest.kt
git commit -m "feat: map A_OPEN refusal to AdbStreamOpenException"
```

---

## Task 7: `AdbSync` — internal `AdbSyncFailException`

Replace the bare `IOException`s in the sync codec with an internal signal the high-level methods can catch, and make `send` report a `FAIL` message symmetrically with `recv`.

**Files:**
- Modify: `dadb/src/main/kotlin/dadb/AdbSync.kt:46-97`
- Test: `dadb/src/test/kotlin/dadb/DadbResultTest.kt` (new file; sync tests here)

- [ ] **Step 1: Write the failing tests**

Create `dadb/src/test/kotlin/dadb/DadbResultTest.kt`:

```kotlin
package dadb

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class DadbResultTest {

    @Test
    fun syncRecvFailThrowsSyncFailException() {
        val stream = FakeAdbStream(syncFailBuffer("No such file or directory"))
        val sync = AdbSyncStream(stream)
        val e = assertFailsWith<AdbSyncFailException> { sync.recv(Buffer(), "/missing") }
        assertThat(e.reason).isEqualTo("No such file or directory")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :dadb:compileTestKotlin`
Expected: FAIL — unresolved `AdbSyncFailException`.

- [ ] **Step 3: Add the exception and use it**

In `dadb/src/main/kotlin/dadb/AdbSync.kt`, add the internal exception just below the imports (after line 23):

```kotlin
/** Internal signal that an adbd sync operation returned FAIL (e.g. file not found / permission
 *  denied). Caught by Dadb.push/pull and folded into a [SyncResult.Failure]; never surfaced raw
 *  from the high-level API. Not an AdbException — the transport is healthy. */
internal class AdbSyncFailException(val reason: String) : IOException(reason)
```

Replace the `send` tail (line 69-70):

```kotlin
        val packet = readPacket()
        if (packet.id == FAIL) {
            val message = stream.source.readString(packet.arg.toLong(), StandardCharsets.UTF_8)
            throw AdbSyncFailException(message)
        }
        if (packet.id != OKAY) throw AdbProtocolException("Unexpected sync packet id: ${packet.id}")
```

Replace the `recv` FAIL/unexpected handling (lines 86-90):

```kotlin
            if (packet.id == FAIL) {
                val message = stream.source.readString(packet.arg.toLong(), StandardCharsets.UTF_8)
                throw AdbSyncFailException(message)
            }
            if (packet.id != DATA) throw AdbProtocolException("Unexpected sync packet id: ${packet.id}")
```

(`AdbSync.kt` already imports `java.io.IOException`, `okio.*`, and `StandardCharsets`; `AdbProtocolException` shares the package.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :dadb:test --tests dadb.DadbResultTest`
Expected: PASS (1 test)

- [ ] **Step 5: Commit**

```bash
git add dadb/src/main/kotlin/dadb/AdbSync.kt dadb/src/test/kotlin/dadb/DadbResultTest.kt
git commit -m "feat: AdbSync throws internal AdbSyncFailException on FAIL"
```

---

## Task 8: `Dadb.push` / `Dadb.pull` → `SyncResult`

**Files:**
- Modify: `dadb/src/main/kotlin/dadb/Dadb.kt:46-68`
- Test: `dadb/src/test/kotlin/dadb/DadbResultTest.kt` (add tests)

- [ ] **Step 1: Write the failing tests**

Add these methods to `DadbResultTest` in `dadb/src/test/kotlin/dadb/DadbResultTest.kt`:

```kotlin
    @Test
    fun pullMissingFileReturnsSyncFailure() {
        val dadb = FakeDadb { FakeAdbStream(syncFailBuffer("No such file or directory")) }
        val result = dadb.pull(Buffer(), "/missing")
        assertThat(result).isInstanceOf(SyncResult.Failure::class.java)
        assertThat((result as SyncResult.Failure).reason).isEqualTo("No such file or directory")
    }

    @Test
    fun pushSuccessReturnsSyncSuccess() {
        val dadb = FakeDadb { FakeAdbStream(syncOkayBuffer()) }
        val result = dadb.push(Buffer().also { it.writeUtf8("hi") }, "/data/local/tmp/hi", 0b110_100_100, 0L)
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :dadb:compileTestKotlin`
Expected: FAIL — `pull`/`push` return `Unit`, so `assertThat(result)` does not type-check.

- [ ] **Step 3: Change the signatures to return `SyncResult`**

In `dadb/src/main/kotlin/dadb/Dadb.kt`, replace the push/pull/openSync block (lines 46-74). Note `openSync` is unchanged except for its `@Throws` annotation, kept here for context:

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :dadb:test --tests dadb.DadbResultTest`
Expected: PASS. The module still compiles at this point: changing `push`/`pull` to return `SyncResult` while `install`/`uninstall`/`root` still return `Unit` is fine (their call sites in `pmInstall`/`installMultiple`/`DadbTest` ignore the new return value, which is legal Kotlin). `AdbException` resolves because it is in the same package.

> **Note for the implementer:** Tasks 8–12 each edit `Dadb.kt` and each leaves the module compiling and its targeted `DadbResultTest` cases green. The emulator-dependent assertions in `DadbTest` are validated once at the end of Task 12 / Final Verification.

- [ ] **Step 5: Commit**

```bash
git add dadb/src/main/kotlin/dadb/Dadb.kt dadb/src/test/kotlin/dadb/DadbResultTest.kt
git commit -m "feat: push/pull return SyncResult"
```

---

## Task 9: `Dadb.uninstall` → `UninstallResult`

**Files:**
- Modify: `dadb/src/main/kotlin/dadb/Dadb.kt:201-207`
- Test: `dadb/src/test/kotlin/dadb/DadbResultTest.kt` (add tests)

- [ ] **Step 1: Write the failing tests**

Add to `DadbResultTest`:

```kotlin
    @Test
    fun uninstallNonZeroExitReturnsFailureWithExitCode() {
        val dadb = FakeDadb {
            FakeAdbStream(shellV2Buffer(stdout = "Failure [DELETE_FAILED_INTERNAL_ERROR]\n", exitCode = 1))
        }
        val result = dadb.uninstall("com.example.absent")
        assertThat(result).isInstanceOf(UninstallResult.Failure::class.java)
        result as UninstallResult.Failure
        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.reason).contains("DELETE_FAILED_INTERNAL_ERROR")
    }

    @Test
    fun uninstallZeroExitReturnsSuccess() {
        val dadb = FakeDadb { FakeAdbStream(shellV2Buffer(stdout = "Success\n", exitCode = 0)) }
        assertThat(dadb.uninstall("com.example")).isInstanceOf(UninstallResult.Success::class.java)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :dadb:compileTestKotlin`
Expected: FAIL — `uninstall` returns `Unit`.

- [ ] **Step 3: Change `uninstall`**

In `dadb/src/main/kotlin/dadb/Dadb.kt`, replace `uninstall` (lines 201-207):

```kotlin
    @Throws(AdbException::class)
    fun uninstall(packageName: String): UninstallResult {
        val response = shell("cmd package uninstall $packageName")
        return if (response.exitCode == 0) {
            UninstallResult.Success
        } else {
            UninstallResult.Failure(reason = response.allOutput, exitCode = response.exitCode)
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :dadb:test --tests dadb.DadbResultTest` (or defer to Task 12's combined run, per the Task 8 note)
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add dadb/src/main/kotlin/dadb/Dadb.kt dadb/src/test/kotlin/dadb/DadbResultTest.kt
git commit -m "feat: uninstall returns UninstallResult"
```

---

## Task 10: `Dadb.install` (+ `pmInstall`) → `InstallResult`

This also fixes a latent bug: the current `pmInstall` ignores the `pm install` result entirely.

**Files:**
- Modify: `dadb/src/main/kotlin/dadb/Dadb.kt:76-110`
- Test: `dadb/src/test/kotlin/dadb/DadbResultTest.kt` (add tests)

- [ ] **Step 1: Write the failing tests** (cmd-path; the pm-path is covered by the emulator suite)

Add to `DadbResultTest`:

```kotlin
    @Test
    fun installCmdPathFailureReturnsInstallFailure() {
        val dadb = FakeDadb(features = setOf("cmd")) {
            FakeAdbStream(Buffer().also { it.writeUtf8("Failure [INSTALL_FAILED_INVALID_APK]") })
        }
        val result = dadb.install(Buffer().also { it.writeUtf8("apk") }, 3L)
        assertThat(result).isInstanceOf(InstallResult.Failure::class.java)
        assertThat((result as InstallResult.Failure).reason).contains("INSTALL_FAILED_INVALID_APK")
    }

    @Test
    fun installCmdPathSuccessReturnsSuccess() {
        val dadb = FakeDadb(features = setOf("cmd")) {
            FakeAdbStream(Buffer().also { it.writeUtf8("Success\n") })
        }
        val result = dadb.install(Buffer().also { it.writeUtf8("apk") }, 3L)
        assertThat(result).isInstanceOf(InstallResult.Success::class.java)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :dadb:compileTestKotlin`
Expected: FAIL — `install` returns `Unit`.

- [ ] **Step 3: Change `install` and `pmInstall`**

In `dadb/src/main/kotlin/dadb/Dadb.kt`, replace `install(file)`, `install(source, size)`, and `pmInstall` (lines 76-110):

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :dadb:test --tests dadb.DadbResultTest` (or defer to Task 12's combined run)
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add dadb/src/main/kotlin/dadb/Dadb.kt dadb/src/test/kotlin/dadb/DadbResultTest.kt
git commit -m "feat: install returns InstallResult and checks the pm-install result"
```

---

## Task 11: `Dadb.installMultiple` → `InstallResult`

Translate every `throw IOException(...)` operation failure in both the `cmd` and `pm` paths into an `InstallResult.Failure` return. Also fixes the `Dadb.kt:145` message bug (`$commitStream` → the response). This path is verified by the emulator suite (`DadbTest.installMultiple…`); update those tests to the new return type.

**Files:**
- Modify: `dadb/src/main/kotlin/dadb/Dadb.kt:112-199`
- Test: `dadb/src/test/kotlin/dadb/DadbTest.kt` (update existing emulator assertions)

- [ ] **Step 1: Rewrite `installMultiple`**

In `dadb/src/main/kotlin/dadb/Dadb.kt`, replace `installMultiple` (lines 112-199):

```kotlin
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
```

- [ ] **Step 2: Update the emulator tests in `DadbTest.kt`**

Find the `installMultiple` / `install` assertions in `dadb/src/test/kotlin/dadb/DadbTest.kt`. Where a test previously called `dadb.install(apk)` / `dadb.installMultiple(apks)` and expected no exception, assert success instead. For example, a test body like:

```kotlin
        localEmulator { dadb ->
            dadb.install(apkFile)
        }
```

becomes:

```kotlin
        localEmulator { dadb ->
            assertThat(dadb.install(apkFile)).isInstanceOf(InstallResult.Success::class.java)
        }
```

Apply the same `assertThat(...).isInstanceOf(InstallResult.Success::class.java)` to each `install`/`installMultiple` call site, and update any `uninstall`/`push`/`pull` call sites to their new result types (`UninstallResult.Success`, `SyncResult.Success`). Add `import com.google.common.truth.Truth.assertThat` if missing.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :dadb:compileKotlin :dadb:compileTestKotlin`
Expected: PASS (whole module compiles; combined test run happens in Task 12)

- [ ] **Step 4: Commit**

```bash
git add dadb/src/main/kotlin/dadb/Dadb.kt dadb/src/test/kotlin/dadb/DadbTest.kt
git commit -m "feat: installMultiple returns InstallResult; fix finalize message bug"
```

---

## Task 12: `Dadb.root` / `unroot` → `RootResult`, `@Throws` sweep, full suite

**Files:**
- Modify: `dadb/src/main/kotlin/dadb/Dadb.kt:28-247` (root/unroot + remaining `@Throws` annotations)
- Test: `dadb/src/test/kotlin/dadb/DadbResultTest.kt` (add root tests)

- [ ] **Step 1: Write the failing tests**

Add to `DadbResultTest` in `dadb/src/test/kotlin/dadb/DadbResultTest.kt`:

```kotlin
    @Test
    fun rootFailureReturnsRootFailure() {
        // root: service replies with a non-restarting line, terminated by '\n'.
        val dadb = FakeDadb {
            FakeAdbStream(Buffer().also { it.writeUtf8("adbd cannot run as root in production builds\n") })
        }
        val result = dadb.root()
        assertThat(result).isInstanceOf(RootResult.Failure::class.java)
        assertThat((result as RootResult.Failure).reason).contains("production builds")
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :dadb:compileTestKotlin`
Expected: FAIL — `root` returns `Unit`.

- [ ] **Step 3: Rewrite root/unroot and finish the `@Throws` sweep**

In `dadb/src/main/kotlin/dadb/Dadb.kt`, replace `root` and `unroot` (lines 223-239):

```kotlin
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
```

Then change the remaining `@Throws(IOException::class)` annotations on the **public interface methods** to `@Throws(AdbException::class)`: `open` (line 28), `shell` (33), `openShell` (40), `execCmd` (209), `abbExec` (216). Leave `tcpForward`'s `@Throws(InterruptedException::class)` unchanged. Add the import `import dadb.AdbException` is unnecessary (same package), but ensure `Dadb.kt` no longer needs the now-unused `IOException` import only if nothing else uses it — `readMode` still throws `RuntimeException` and the companion still references `IOException` in `waitRootOrClose`'s catch, so **keep** the `okio.*`/`java.io` imports as-is.

> The `@Throws` annotation value must resolve; since `AdbException` is in package `dadb`, no import is needed.

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew :dadb:test`
Expected: PASS for all non-emulator tests — `AdbExceptionTest`, `ResultTypesTest`, `AdbStreamTest`, `AdbConnectionHandshakeTest`, `DadbResultTest`, `MessageQueueTest`, `PKCS8Test`. Emulator-dependent suites (`DadbTestImpl`, `AdbServerTest`, `DadbImplTest`) require a device; if none is attached they will error on connect — that is expected in a non-emulator environment. Confirm the unit suites are green.

- [ ] **Step 5: Commit**

```bash
git add dadb/src/main/kotlin/dadb/Dadb.kt dadb/src/test/kotlin/dadb/DadbResultTest.kt
git commit -m "feat: root/unroot return RootResult; @Throws(AdbException) sweep"
```

---

## Task 13: Update docs & changelog

**Files:**
- Modify: `README.md`, `CHANGELOG.md`

- [ ] **Step 1: Update the README usage examples**

In `README.md`, update the install example and any others to the new return types. Replace:

```kotlin
Dadb.create("localhost", 5555).use { dadb ->
    dadb.install(apkFile)
}
```

with:

```kotlin
Dadb.create("localhost", 5555).use { dadb ->
    when (val result = dadb.install(apkFile)) {
        is InstallResult.Success -> println("installed")
        is InstallResult.Failure -> println("install failed: ${result.reason}")
    }
}
```

Add a short "Error handling" subsection after the usage examples:

```markdown
### Error handling

dadb distinguishes *transport* failures from *operation* failures:

- **Transport failures throw** an `AdbException` (an `IOException`): `AdbConnectException`,
  `AdbAuthException`, `AdbStreamOpenException`, `AdbConnectionClosedException`, `AdbProtocolException`.
  Catch `AdbException` to decide whether to reconnect.
- **Operation outcomes are returned**, never thrown: `install`/`installMultiple` → `InstallResult`,
  `uninstall` → `UninstallResult`, `push`/`pull` → `SyncResult`, `root`/`unroot` → `RootResult`.
  A non-zero `shell` exit code stays a value on `AdbShellResponse`.
```

- [ ] **Step 2: Add a CHANGELOG entry**

Prepend an entry to `CHANGELOG.md` describing the major version's breaking changes (typed `AdbException` hierarchy; result-returning `install`/`installMultiple`/`uninstall`/`push`/`pull`/`root`/`unroot`; mid-stream socket faults now throw `AdbConnectionClosedException` instead of EOF). Match the file's existing entry format.

- [ ] **Step 3: Commit**

```bash
git add README.md CHANGELOG.md
git commit -m "docs: document the adbd-aware error model"
```

---

## Final Verification

- [ ] Run the unit suites and confirm green:
  `./gradlew :dadb:test --tests dadb.AdbExceptionTest --tests dadb.ResultTypesTest --tests dadb.AdbStreamTest --tests dadb.AdbConnectionHandshakeTest --tests dadb.DadbResultTest --tests dadb.MessageQueueTest --tests dadb.PKCS8Test`
- [ ] Confirm the whole module compiles: `./gradlew :dadb:compileKotlin :dadb:compileTestKotlin`
- [ ] Grep for leftover bare operation-failure throws that should now be results:
  `grep -n "throw IOException" dadb/src/main/kotlin/dadb/Dadb.kt dadb/src/main/kotlin/dadb/AdbSync.kt` — expect **no matches** in `Dadb.kt` and `AdbSync.kt`.
- [ ] (If an emulator is available) run `./gradlew :dadb:test` in full and confirm the emulator suites pass, including `open_invalidService_throwsStreamOpenException` and the updated `install`/`installMultiple` assertions.
