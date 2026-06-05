# dadb Error Model Redesign — Design Spec

- **Date:** 2026-06-05
- **Status:** Proposed (awaiting review)
- **Scope:** Direct-to-adbd error handling only (concern #1). Host-server / smart-socket separation (concern #2) is a **separate** effort and explicitly out of scope here.

---

## 1. Problem

`dadb` funnels nearly every failure through bare `java.io.IOException`. The only library-specific exception type, `AdbStreamClosed`, is `internal` and uncatchable by consumers. As a result, a caller cannot programmatically distinguish:

- **"the connection to adbd is dead — reconnect"** (socket reset, handshake failed, auth rejected), from
- **"the connection is fine, but the operation I asked for failed"** (an apk was rejected, a remote file was missing, a command exited non-zero).

The only way to tell them apart today is string-matching exception messages. Concretely, these throw `IOException` for *operation* failures while the connection is perfectly alive (`Dadb.kt`): install rejected (`:93`, `:150`, `:196`), pm session failures (`:121`, `:123`, `:159`, `:162`), finalize failures (`:145`, `:193`), uninstall non-zero — exit code discarded into a string (`:205`), root/unroot refused (`:227`, `:236`); plus sync `FAIL` for a missing/forbidden remote file (`AdbSync.kt:88`).

There is also an **inverse** bug: `AdbStreamImpl.nextMessage` (`AdbStream.kt:117-124`) swallows a real socket fault mid-read and converts it into a clean EOF, so a dropped connection during `shell`/`pull` can masquerade as success — the opposite failure, same root cause (an undifferentiated `IOException` channel).

## 2. Goal & principle

Make the error model **adbd-aware**: every outcome maps to a distinct, documented place the ADB wire protocol (`packages/modules/adb/protocol.txt`) can produce it. One governing principle:

> **An exception means "I could not get an outcome — the transport/connection/auth is in trouble." A returned result means "I got an outcome — here is whether the operation succeeded."**

This mirrors what the most active implementations do: Google's `adblib` (distinct `AdbProtocolErrorException` vs `AdbFailResponseException`; exit codes as values), `adam` (`ShellCommandResult.exitCode`), and Rust `adb_client` (non-zero exit is `Ok(...)`). It also follows JVM convention: root the hierarchy at `IOException` (as `java.net` does with `SocketException`), and treat an expected "no" as data, not a throw (OkHttp's "a response is not an exception"; Kotlin's sealed-result guidance).

## 3. Scope

**In scope** — failure modes reachable by talking straight to adbd over the socket:

| adbd failure mode (`protocol.txt` unless noted) | Maps to |
|---|---|
| TCP connect refused/timeout; `A_CNXN` handshake never completes / version mismatch / unparseable banner | `AdbConnectException` (throw) |
| `A_AUTH` rejected — keys refused or pubkey prompt denied; device stays UNAUTHORIZED | `AdbAuthException` (throw) |
| `A_OPEN` answered with `A_CLSE` — adbd refused to open the stream (bad/forbidden service, device offline). Connection still alive. | `AdbStreamOpenException` (throw) |
| Unexpected socket fault (EOF/RST) **mid-stream** | `AdbConnectionClosedException` (throw) |
| Malformed/unexpected apacket — checksum/magic/length desync | `AdbProtocolException` (throw) |
| `sync:` returns `FAIL` + msg — ENOENT, EACCES (`SYNC.TXT`) | `SyncResult.Failure` (**result**) |
| `shell,v2:` `kIdExit` exit code (`shell_service.cpp`) | `exitCode` in `AdbShellResponse` (**result**, already exists) |

**Out of scope (deliberately):**
- The host-server **smart-socket** protocol (`SERVICES.TXT`, 4-hex-length `OKAY`/`FAIL`) and its failures (`device offline`, `not found`, `more than one device`). That is the adb-server path (`dadb.adbserver.*`) and belongs to concern #2. adbd never speaks the smart-socket protocol; it has no place in this model.
- Restructuring `discover()`/`list()`/`AdbServer` (concern #2).
- Concurrency/resource fixes found during review (separate work).

## 4. Exception hierarchy

All types extend a sealed `AdbException`, which extends `IOException` so **every existing `catch (IOException)` keeps catching them** and existing `@Throws(IOException::class)` clauses remain valid.

```kotlin
package dadb

import java.io.IOException

/** Root of all dadb transport/connection failures. Extends IOException for backward-compat. */
sealed class AdbException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Could not establish the connection: TCP connect/timeout, or the A_CNXN handshake failed.
 *  Nothing ran — safe to retry by reconnecting. */
class AdbConnectException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** A_AUTH was rejected (keys refused / on-device prompt denied; device UNAUTHORIZED).
 *  Reconnecting with the same key will not help — fix or accept the key. */
class AdbAuthException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** adbd refused to OPEN the stream (A_OPEN answered with A_CLSE): unknown/forbidden service
 *  string, or device offline. The underlying connection is still usable. */
class AdbStreamOpenException(val destination: String, message: String, cause: Throwable? = null)
    : AdbException(message, cause)

/** An established connection/stream died unexpectedly (socket EOF/RST mid-operation).
 *  Reconnect — but the operation MAY have partially executed; re-check state before retrying
 *  anything non-idempotent. */
class AdbConnectionClosedException(message: String, cause: Throwable? = null)
    : AdbException(message, cause)

/** The peer sent a malformed or unexpected apacket (checksum/magic/length desync). There is no
 *  reliable way to re-sync — the connection must be closed and re-established. */
class AdbProtocolException(message: String, cause: Throwable? = null) : AdbException(message, cause)
```

**On retryability:** we deliberately do **not** add a `retryable: Boolean` flag. A flag would lie in the one case that matters most — `AdbConnectionClosedException` — where adbd's protocol gives no way to know whether a mid-stream `pm install` already committed (gRPC's "only retry if the server never saw the request" problem). Instead, retry semantics are encoded in the **type** (the JDBC `SQLTransientException` / adblib approach) and documented per type: `AdbConnectException` = nothing ran, safe to retry; `AdbConnectionClosedException` = re-check state first.

### Mapping to current code

- `AdbConnection.connect` (`AdbConnection.kt`): `IOException("Connection failed", :118)` and the feature-parse `IOException` (`:134`, an unparseable handshake banner) → `AdbConnectException` (both are connection-establishment failures); auth `checkNotNull`/`check` (`:105-106`) → `AdbAuthException`. Raw socket faults from `AdbReader`/`AdbWriter` during the handshake → wrapped as `AdbConnectException(cause = e)`. `AdbProtocolException` is reserved for an unexpected/malformed apacket on an **already-established** connection (desync), not for handshake-time failures.
- `AdbConnection.open` (`AdbConnection.kt:43-55`): when `take(localId, CMD_OKAY)` sees a peer `CLSE` (currently surfaces as the internal `AdbStreamClosed`), throw `AdbStreamOpenException(destination)`. A raw socket fault here → `AdbConnectionClosedException`.
- `AdbStreamImpl.nextMessage` (`AdbStream.kt:117-124`) — the behavioral fix, see §6.
- `AdbStreamClosed` (internal) is retained only as an **internal** signal that the peer closed a stream; it is translated to the appropriate public type at the boundary (open vs mid-stream). It is never thrown to consumers.

## 5. Result types

Expected, in-band adbd outcomes become return values. Per-operation sealed types keep each call site's `when` exhaustive and readable.

```kotlin
sealed interface InstallResult {
    object Success : InstallResult
    /** `reason` is the raw pm/`cmd package` response (e.g. "Failure [INSTALL_FAILED_...]"). */
    data class Failure(val reason: String) : InstallResult
}

sealed interface SyncResult {           // push() and pull()
    object Success : SyncResult
    /** `reason` is the adbd sync FAIL message (e.g. "No such file or directory"). */
    data class Failure(val reason: String) : SyncResult
}

sealed interface CommandResult {        // uninstall(), root(), unroot()
    object Success : CommandResult
    data class Failure(val reason: String) : CommandResult
}
```

`shell()`/`openShell()` are unchanged — `AdbShellResponse(output, errorOutput, exitCode)` already carries the exit code as a value and never throws on non-zero. It is the template the rest of the API now follows.

**Division of labor — results vs throws within an operation.** Low-level stream codecs keep throwing internally; the high-level operation catches the *operation-failure* signal and returns a `Failure`, while letting *transport* exceptions propagate:

- `AdbSyncStream.send`/`recv` (`AdbSync.kt`) throw a new internal `AdbSyncFailException(message)` for a sync `FAIL`/unexpected-id (replacing the bare `IOException` at `:70`, `:88`, `:90`). `Dadb.push`/`pull` catch it → `SyncResult.Failure(message)`; any `AdbConnectionClosedException`/`AdbProtocolException` propagates untouched.
- `Dadb.install`/`installMultiple` return `InstallResult` by inspecting the `cmd`/`pm` response instead of `throw IOException("Install failed: …")`.
- `Dadb.uninstall`/`root`/`unroot` return `CommandResult` by inspecting the exit code / restart response instead of throwing.

So every high-level op may still **throw** an `AdbException` (transport/auth died → no outcome), and otherwise **returns** a result (an outcome was obtained, success or failure).

## 6. Behavioral fix: stop masquerading transport death as EOF

`AdbStreamImpl.nextMessage` currently swallows *all* `IOException`s into a clean EOF. The fix distinguishes the two cases the protocol actually produces:

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

A peer `A_CLSE` remains a legitimate EOF (it is how adbd signals a service's output is done); only a genuine socket fault now surfaces as `AdbConnectionClosedException`. This is the one behavioral break in the spec.

## 7. Per-operation migration table

| Operation | Today | After |
|---|---|---|
| `open(dest): AdbStream` | throws `IOException` (incl. internal `AdbStreamClosed`) | throws `AdbStreamOpenException` / `AdbConnectException` / `AdbConnectionClosedException` / `AdbAuthException` (all `: IOException`) |
| `shell(cmd): AdbShellResponse` | exit code in result; transport → `IOException` | unchanged; transport → typed `AdbException` |
| `install(...)`: `Unit` | throws `IOException` on rejection | **returns `InstallResult`**; transport → `AdbException` |
| `installMultiple(...)`: `Unit` | throws `IOException` on rejection | **returns `InstallResult`**; transport → `AdbException` |
| `uninstall(pkg)`: `Unit` | throws `IOException` if exit≠0 | **returns `CommandResult`**; transport → `AdbException` |
| `push(...)`: `Unit` | throws `IOException` on sync FAIL | **returns `SyncResult`**; transport → `AdbException` |
| `pull(...)`: `Unit` | throws `IOException` on sync FAIL/missing file | **returns `SyncResult`**; transport → `AdbException` |
| `root()` / `unroot()`: `Unit` | throws `IOException` if refused | **returns `CommandResult`**; transport → `AdbException` |
| `AdbSyncStream.send`/`recv` | throws bare `IOException` | throws internal `AdbSyncFailException` (op) / `AdbException` (transport) |

`AdbStreamClosed` becomes a non-public translation detail. No public method loses its `@Throws(IOException::class)` clause (every new exception is an `IOException`).

## 8. Backward compatibility & migration

- **Source-compatible for `catch`:** all new exceptions extend `IOException`; existing `catch (IOException)` blocks keep working and now receive more specific subtypes.
- **Breaking — return types:** `install`, `installMultiple`, `uninstall`, `push`, `pull`, `root`, `unroot` change from `Unit` to result types. Every caller (notably Maestro) must update call sites to inspect the result. This is the deliberate "clean break" chosen for this redesign.
- **Breaking — behavior:** the `nextMessage` fix (§6) means a dropped connection mid-stream now throws instead of looking like clean EOF.
- **Versioning:** ship as a **major** version bump with release notes enumerating the result-type changes and the EOF-behavior change, plus a short migration guide (`when (result) { … }` snippets).
- **No change** to the wire-protocol classes' on-the-wire behavior (`AdbReader`/`AdbWriter`/`AdbMessageQueue`/`AdbKeyPair`); only how their failures are typed/surfaced.

## 9. Testing strategy

- **Unit (no emulator):** drive `AdbConnection.connect` and `open` against in-memory okio `Buffer`s (as `AdbStreamTest` already does) to assert each apacket failure maps to the right type — `A_CNXN` failure → `AdbConnectException`, `A_AUTH` rejection → `AdbAuthException`, `A_OPEN`→`A_CLSE` → `AdbStreamOpenException`, mid-stream socket fault → `AdbConnectionClosedException`, malformed apacket → `AdbProtocolException`. Cover the `nextMessage` split: peer `A_CLSE` → EOF (`read() == -1`), socket fault → throw.
- **Sync codec:** feed a `FAIL`+message packet → assert `AdbSyncStream.recv` throws `AdbSyncFailException` and `Dadb.pull` returns `SyncResult.Failure(message)`.
- **Emulator (existing `DadbTest` style):** install a bad/duplicate apk → `InstallResult.Failure`; uninstall an absent package → `CommandResult.Failure`; pull a missing path → `SyncResult.Failure`; happy paths → `Success`.

## 10. Future / not now

- Concern #2: split the direct-adbd module from the adb-server module; make binary-spawning opt-in; give server-backed connections a distinct type. The smart-socket error types (`AdbHostResponseException` carrying `service`/`failMessage`) belong to that effort, not this one.
- Optional `shellOrThrow`-style convenience wrappers, if callers ask for them after the result migration.

## 11. References

- AOSP `packages/modules/adb`: `protocol.txt` (A_CNXN/A_AUTH/A_OPEN/A_OKAY/A_WRTE/A_CLSE), `SERVICES.TXT` (smart-socket — out of scope), `SYNC.TXT` (sync FAIL), `shell_service.cpp` (`kIdExit`).
- Google `adblib`: `AdbProtocolErrorException` vs sealed `AdbFailResponseException`; shell-v2 exit code as a flow value.
- `adam` (Malinskiy): `ShellCommandResult.exitCode`; the cautionary conflation of transport-dead and FAIL into one `RequestRejectedException`.
- Rust `adb_client`: `RustADBError` — non-zero exit is `Ok`, FAIL string carried in `ADBRequestFailed`.
- Conventions: Effective Java items 70–73 (checked-for-recoverable, exception translation); JDBC `SQLTransientException`/`SQLNonTransientException`; OkHttp "a response is not an exception"; Kotlin sealed-result guidance (Elizarov).
