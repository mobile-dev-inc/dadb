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

import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * FULL-STACK test for the wedged-adbd write hang and its fix. No mocks: a real [Dadb] talks to a
 * real adbd on localhost:5555. The wedge is induced exactly as the production incident: freeze
 * adbd mid-operation (SIGSTOP the emulator process) so the socket stops draining.
 *
 * Background:
 *   - SO_TIMEOUT (Socket.setSoTimeout, exposed as Dadb.create(socketTimeout=)) bounds READS only.
 *   - Every shell/pull/push starts with an OPEN write, and pushes/installs stream WRITE messages;
 *     none of those had a deadline, so a wedged adbd made the write block forever (burning
 *     Maestro's 15-minute watchdog and surfacing as a non-retryable customer error).
 *
 * The fix always bounds writes with a fixed internal deadline ([AdbConnection.WRITE_TIMEOUT_MILLIS])
 * applied to okio's socket sink timeout. okio chunks each write at TIMEOUT_WRITE_SIZE and arms its
 * SocketAsyncTimeout per chunk; on expiry SocketAsyncTimeout.timedOut() closes the socket and the
 * write throws SocketTimeoutException. Closing the socket also invalidates the connection, so
 * DadbImpl rebuilds it on the next op. It is a correctness guard, not a tunable.
 *
 * Requires: a booted emulator on console port 5554 / adb port 5555.
 */
class WriteTimeoutTest {

    private sealed class Outcome {
        object Completed : Outcome()
        object Hung : Outcome()
        data class Threw(val cause: Throwable?) : Outcome()
    }

    private fun emulatorPid(): String =
        ProcessBuilder("pgrep", "-f", "qemu-system.*-port 5554")
            .start().inputStream.bufferedReader().readText().trim().lines().first().trim()

    private fun signal(pid: String, sig: String) {
        ProcessBuilder("kill", sig, pid).start().waitFor()
    }

    /** Runs [op] on a daemon thread, capping the wait at [capSeconds] so a real hang can't stall CI. */
    private fun runBounded(capSeconds: Long, op: () -> Unit): Pair<Outcome, Long> {
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "dadb-op").apply { isDaemon = true } }
        val start = System.currentTimeMillis()
        val f: Future<*> = exec.submit(op)
        val outcome = try {
            f.get(capSeconds, TimeUnit.SECONDS); Outcome.Completed
        } catch (e: TimeoutException) {
            Outcome.Hung
        } catch (e: java.util.concurrent.ExecutionException) {
            Outcome.Threw(e.cause)
        } finally {
            f.cancel(true); exec.shutdownNow()
        }
        return outcome to (System.currentTimeMillis() - start)
    }

    /**
     * FIX: with adbd frozen, a shell op whose 32MB OPEN write fills the TCP buffers must fail fast
     * with a SocketTimeoutException, bounded by the internal write deadline well under the watchdog.
     * Before the fix this hangs (socketTimeout covers reads only). The cap is generous vs the write
     * deadline so a failure to bound is observable as a Hung outcome, not just a slow pass.
     */
    @Test
    fun wedgedWrite_isBoundedByWriteTimeout() {
        val pid = emulatorPid()
        val writeBudgetMs = AdbConnection.WRITE_TIMEOUT_MILLIS
        val dadb = Dadb.create("localhost", 5555, connectTimeout = 3000, socketTimeout = 2000)
        try {
            val warmup = dadb.shell("echo warmup").allOutput.trim()
            println("[WRITE] warmup over real adbd -> '$warmup'")

            signal(pid, "-STOP") // wedge: real adbd stops servicing the socket
            val bigCommand = "x".repeat(32 * 1024 * 1024) // 32MB OPEN payload fills TCP buffers
            val capSeconds = (writeBudgetMs / 1000) + 10 // generous headroom so a true hang reads as Hung
            val (outcome, ms) = runBounded(capSeconds) { dadb.shell(bigCommand) }
            println("[WRITE] internal writeTimeout=${writeBudgetMs}ms, adbd FROZEN -> $outcome after ${ms}ms")

            when (outcome) {
                is Outcome.Hung -> fail("Write hung past the cap — internal write deadline not enforced")
                is Outcome.Completed -> fail("Write completed unexpectedly against a frozen adbd")
                is Outcome.Threw -> {
                    val cause = outcome.cause
                    assertTrue(
                        cause is SocketTimeoutException,
                        "Expected SocketTimeoutException from the bounded write, got ${cause?.javaClass?.name}: ${cause?.message}"
                    )
                    // Bounded near the internal deadline (allow scheduling slack), far below the cap.
                    assertTrue(
                        ms < writeBudgetMs + 5000L,
                        "Write should be bounded near ${writeBudgetMs}ms, took ${ms}ms"
                    )
                }
            }

            // Connection self-heals: the timed-out socket was closed, so the next op rebuilds it.
            signal(pid, "-CONT")
            val recovered = dadb.shell("echo recovered").allOutput.trim()
            println("[WRITE] recovered after rebuild -> '$recovered'")
            assertTrue(recovered == "recovered", "Expected connection to rebuild after timeout, got '$recovered'")
        } finally {
            signal(pid, "-CONT")
            try { dadb.close() } catch (ignore: Throwable) {}
        }
    }

    /**
     * CONTROL: with adbd frozen, a small shell read is still bounded by SO_TIMEOUT and throws
     * SocketTimeoutException within socketTimeout. Proves the read path is unchanged by the fix.
     */
    @Test
    fun wedgedRead_isBoundedBySocketTimeout() {
        val pid = emulatorPid()
        val socketTimeout = 2000
        val dadb = Dadb.create("localhost", 5555, connectTimeout = 3000, socketTimeout = socketTimeout)
        try {
            val warmup = dadb.shell("echo warmup").allOutput.trim()
            println("[READ ] warmup over real adbd -> '$warmup'")

            signal(pid, "-STOP")
            val (outcome, ms) = runBounded(capSeconds = 8) { dadb.shell("echo hi") }
            println("[READ ] socketTimeout=${socketTimeout}ms, adbd FROZEN -> $outcome after ${ms}ms")

            assertTrue(
                outcome is Outcome.Threw && outcome.cause is SocketTimeoutException,
                "Expected SocketTimeoutException from the bounded read, got $outcome"
            )
            assertTrue(ms < 2L * socketTimeout, "Read should be bounded under ${2 * socketTimeout}ms, took ${ms}ms")
        } finally {
            signal(pid, "-CONT")
            try { dadb.close() } catch (ignore: Throwable) {}
        }
    }
}
