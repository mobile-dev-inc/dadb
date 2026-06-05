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

All types extend a sealed `AdbException`, which extends `IOException`. This is a merit choice, not a compatibility one: these failures genuinely *are* socket/transport I/O faults and they are recoverable (reconnect) — the textbook case for a checked `IOException` (Effective Java item 70), mirroring `java.net`'s `SocketException : IOException`. It is also consistent with our own surface: `AdbStream` exposes okio `Source`/`Sink`, whose reads/writes already throw `IOException`, so a consumer streaming from adbd is already handling `IOException`.

**Contract guarantee:** no bare `IOException` ever leaks from a public method — every transport fault is wrapped as one of the subtypes below. Public methods that can fail at the transport are therefore annotated `@Throws(AdbException::class)` (a strictly more precise Java signal than the current `@Throws(IOException::class)`).

```kotlin
package dadb

import java.io.IOException

/** Root of all dadb transport/connection failures. Extends IOException because these are
 *  genuine, recoverable socket I/O faults, consistent with the okio stream surface. */
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

Expected, in-band adbd outcomes become return values, never exceptions. Each **named high-level operation returns its own domain result type**, so peer operations are peers in the API — `install` and `uninstall` both return an `…Result`, not one a result and the other a raw shell response. Each `Failure` carries the richest honest detail the underlying adbd interaction produced, so the wrapper never *hides* what adbd did even though it presents a uniform surface.

### Two-axis consistency rule

The `Dadb` methods sit at different distances from adbd — `open()` is 1:1 with an `A_OPEN`; `shell`/`push`/`pull`/`uninstall` are single-service; `install`/`installMultiple`/`root`/`unroot` are composites that orchestrate several adbd ops. Consistency comes from applying one rule across all of them, on two independent axes:

- **Transport/connection failures are universal.** Every operation can throw the single `AdbException` hierarchy (§4), because every operation ultimately goes through `open()` and the stream. These are *never* operation-specific.
- **Outcome results are operation-specific.** Each named operation gets its own `…Result { Success | Failure(…) }`; peers share one type. A composite folds any constituent failure into *its own* verdict rather than leaking a lower-level result (e.g. an `install` whose `pm`-path push sync-FAILs returns `InstallResult.Failure`, not a `SyncResult`).

This is the deliberate choice of API-surface uniformity ("Philosophy B") over mechanism-mirroring: `uninstall` gets a domain type even though it is one shell command, accepting a small amount of wrapping in exchange for `install`/`uninstall` being true peers. The non-obfuscation constraint is preserved by carrying adbd's real detail in the `Failure` payload.

| Operation(s) | Result type |
|---|---|
| `install`, `installMultiple` | `InstallResult` |
| `uninstall` | `UninstallResult` |
| `push`, `pull` | `SyncResult` |
| `root`, `unroot` | `RootResult` |
| `shell`, `openShell` | `AdbShellResponse` / `AdbShellStream` (generic primitive — see below) |

```kotlin
sealed interface InstallResult {        // install(), installMultiple()
    object Success : InstallResult
    /** Raw pm/`cmd package` verdict, surfaced opaquely — exactly as the canonical adb client does:
     *  client/adb_install.cpp checks `strncmp("Success", buf, 7)` and prints any other response
     *  verbatim. The `INSTALL_FAILED_*` codes are a frameworks/base (PackageManager) concept, NOT
     *  part of the adbd contract, so dadb does not parse them into an enum. */
    data class Failure(val reason: String) : InstallResult
}

sealed interface UninstallResult {      // uninstall()
    object Success : UninstallResult
    /** `uninstall` is `cmd package uninstall` over the shell service. Failure preserves adbd's
     *  actual process `exitCode` and combined output (`reason`) so the domain wrapper hides
     *  nothing — the whole point of choosing a wrapper here is peer-symmetry with install, not
     *  concealment. */
    data class Failure(val reason: String, val exitCode: Int) : UninstallResult
}

sealed interface SyncResult {           // push(), pull()
    object Success : SyncResult
    /** The adbd sync FAIL message, e.g. "No such file or directory" (SYNC.TXT). */
    data class Failure(val reason: String) : SyncResult
}

sealed interface RootResult {           // root(), unroot()
    object Success : RootResult
    /** The adbd root:/unroot: service response line, e.g.
     *  "adbd cannot run as root in production builds". This service has no exit code. */
    data class Failure(val reason: String) : RootResult
}
```

**Granularity:** the named operations collapse to four domain types (`InstallResult`, `UninstallResult`, `SyncResult`, `RootResult`), peers sharing one type. Not a single generic `Result<T>` — the `Failure` payloads genuinely differ (uninstall carries an exit code; the others do not), and a generic wrapper would erase those distinctions.

**`shell`/`openShell` stay `AdbShellResponse`/`AdbShellStream`.** These are the *generic shell primitive* callers build their own commands on, not a named package-lifecycle operation, so they expose the shell service's outcome directly (`exitCode` as a value, never thrown). `shell` is the tool; `install`/`uninstall`/etc. are the abstractions built with it.

**Division of labor — results vs throws within an operation.** Low-level stream codecs keep throwing internally; the high-level operation catches the *operation-failure* signal and returns a `Failure`, while letting *transport* exceptions propagate:

- `AdbSyncStream.send`/`recv` (`AdbSync.kt`) throw a new internal `AdbSyncFailException(message)` for a sync `FAIL`/unexpected-id (replacing the bare `IOException` at `:70`, `:88`, `:90`). `Dadb.push`/`pull` catch it → `SyncResult.Failure(message)`; any `AdbConnectionClosedException`/`AdbProtocolException` propagates untouched.
- `Dadb.install`/`installMultiple` return `InstallResult` by inspecting the `cmd`/`pm` response instead of `throw IOException("Install failed: …")`.
- `Dadb.uninstall` runs the shell command and maps the `AdbShellResponse` into `UninstallResult` — `exitCode == 0` → `Success`, else `Failure(reason = response.allOutput, exitCode = response.exitCode)`.
- `Dadb.root`/`unroot` return `RootResult` by classifying the adbd service's response line instead of throwing.

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

A peer `A_CLSE` remains a legitimate EOF (it is how adbd signals a service's output is done); only a genuine socket fault now surfaces as `AdbConnectionClosedException`. The previous blanket swallow was a latent bug — silently presenting a dropped connection as a clean end-of-stream — so this is simply the correct behavior, not a reluctant break.

## 7. Per-operation migration table

| Operation | Today | After |
|---|---|---|
| `open(dest): AdbStream` | throws `IOException` (incl. internal `AdbStreamClosed`) | throws `AdbStreamOpenException` / `AdbConnectException` / `AdbConnectionClosedException` / `AdbAuthException` (all `AdbException`) |
| `shell(cmd): AdbShellResponse` | exit code in result; transport → `IOException` | unchanged; transport → typed `AdbException` |
| `install(...)`: `Unit` | throws `IOException` on rejection | **returns `InstallResult`**; transport → `AdbException` |
| `installMultiple(...)`: `Unit` | throws `IOException` on rejection | **returns `InstallResult`**; transport → `AdbException` |
| `uninstall(pkg)`: `Unit` | throws `IOException` if exit≠0 | **returns `UninstallResult`** (`Failure` carries `reason` + `exitCode`); transport → `AdbException` |
| `push(...)`: `Unit` | throws `IOException` on sync FAIL | **returns `SyncResult`**; transport → `AdbException` |
| `pull(...)`: `Unit` | throws `IOException` on sync FAIL/missing file | **returns `SyncResult`**; transport → `AdbException` |
| `root()` / `unroot()`: `Unit` | throws `IOException` if refused | **returns `RootResult`**; transport → `AdbException` |
| `AdbSyncStream.send`/`recv` | throws bare `IOException` | throws internal `AdbSyncFailException` (op) / `AdbException` (transport) |

`AdbStreamClosed` becomes a non-public translation detail. Public methods that can fail at the transport are annotated `@Throws(AdbException::class)` (precise Java checked signal); methods that return result types only throw `AdbException` when the transport dies before an outcome can be obtained.

## 8. Migration (major version)

This ships as a **major** version bump. Backward compatibility is *not* a design constraint — the contract above is chosen on its merits, and consumers opt into the upgrade when ready. This section exists only to make that opt-in straightforward, not to soften the contract.

Breaking changes consumers will see:

- **Return types:** `install`, `installMultiple`, `uninstall`, `push`, `pull`, `root`, `unroot` change from `Unit` to result types; call sites must inspect the result (`when (result) { is …Success -> …; is …Failure -> … }`).
- **Exception types:** transport/auth failures are now `AdbException` subtypes instead of bare `IOException`/`IllegalStateException`. Code that switched on exception *messages* should switch on *types* instead.
- **Behavior:** a connection dropped mid-stream now throws `AdbConnectionClosedException` instead of presenting as a clean EOF (§6).

Release notes will enumerate these with before/after snippets. (Incidental: because `AdbException : IOException`, a coarse `catch (IOException)` still compiles and catches — but that is a consequence of the correct base type, not a goal, and is not how callers should distinguish failures going forward.)

No on-the-wire behavior of the protocol classes (`AdbReader`/`AdbWriter`/`AdbMessageQueue`/`AdbKeyPair`) changes — only how their failures are typed and surfaced.

## 9. Testing strategy

- **Unit (no emulator):** drive `AdbConnection.connect` and `open` against in-memory okio `Buffer`s (as `AdbStreamTest` already does) to assert each apacket failure maps to the right type — `A_CNXN` failure → `AdbConnectException`, `A_AUTH` rejection → `AdbAuthException`, `A_OPEN`→`A_CLSE` → `AdbStreamOpenException`, mid-stream socket fault → `AdbConnectionClosedException`, malformed apacket → `AdbProtocolException`. Cover the `nextMessage` split: peer `A_CLSE` → EOF (`read() == -1`), socket fault → throw.
- **Sync codec:** feed a `FAIL`+message packet → assert `AdbSyncStream.recv` throws `AdbSyncFailException` and `Dadb.pull` returns `SyncResult.Failure(message)`.
- **Emulator (existing `DadbTest` style):** install a bad/duplicate apk → `InstallResult.Failure`; uninstall an absent package → `UninstallResult.Failure` (non-zero `exitCode` preserved); pull a missing path → `SyncResult.Failure`; `unroot` on a production-style build → `RootResult.Failure`; happy paths → `Success`.

## 10. Future / not now

- Concern #2: split the direct-adbd module from the adb-server module; make binary-spawning opt-in; give server-backed connections a distinct type. The smart-socket error types (`AdbHostResponseException` carrying `service`/`failMessage`) belong to that effort, not this one.
- Optional `shellOrThrow`-style convenience wrappers, if callers ask for them after the result migration.

## 11. References

- AOSP `packages/modules/adb`: `protocol.txt` (A_CNXN/A_AUTH/A_OPEN/A_OKAY/A_WRTE/A_CLSE), `SERVICES.TXT` (smart-socket — out of scope), `SYNC.TXT` (sync FAIL), `shell_service.cpp` (`kIdExit`), `client/adb_install.cpp` (`strncmp("Success", buf, 7)` — failure text is opaque, not structured).
- `frameworks/base` `core/java/android/content/pm/PackageManager.java` — the `INSTALL_FAILED_*` constants. Noted only to document that they live in the framework, *not* the adb/adbd contract, which is why dadb does not parse them.
- Google `adblib`: `AdbProtocolErrorException` vs sealed `AdbFailResponseException`; shell-v2 exit code as a flow value.
- `adam` (Malinskiy): `ShellCommandResult.exitCode`; the cautionary conflation of transport-dead and FAIL into one `RequestRejectedException`.
- Rust `adb_client`: `RustADBError` — non-zero exit is `Ok`, FAIL string carried in `ADBRequestFailed`.
- Conventions: Effective Java items 70–73 (checked-for-recoverable, exception translation); JDBC `SQLTransientException`/`SQLNonTransientException`; OkHttp "a response is not an exception"; Kotlin sealed-result guidance (Elizarov).
