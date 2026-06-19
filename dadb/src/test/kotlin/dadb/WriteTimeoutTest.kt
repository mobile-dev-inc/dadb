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

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Tests that a wedged adb connection fails fast instead of hanging forever.
 *
 * `Socket.setSoTimeout` (Dadb's `socketTimeout`) bounds READS only. Writes — the OPEN that begins
 * every shell/pull/push, plus streamed WRITE payloads — are bounded separately by okio's socket
 * sink timeout (see [AdbConnection.WRITE_TIMEOUT_MILLIS]).
 *
 * Both directions are exercised against a REAL emulator (adb port 5555); the wedge is injected
 * deterministically and portably, with no process freezing:
 *  - WRITE: route dadb through a transparent TCP [Relay] to real adbd, then stop draining the client
 *    side. Real adbd would buffer hundreds of MB, but a relay that stops reading lets only the OS
 *    socket buffers absorb the write before it blocks — so the write deadline fires deterministically.
 *  - READ: run a command that produces no output (`sleep`); the read blocks until SO_TIMEOUT fires.
 *
 * Requires a booted emulator on adb port 5555.
 */
class WriteTimeoutTest {

    /**
     * Transparent byte relay: dadb <-> [Relay] <-> real adbd. Forwards both directions untouched
     * (so the real ADB handshake/protocol run against real adbd); pausing the client->target pump
     * wedges writes. Accepts repeatedly so a connection can be rebuilt after a timeout closes it.
     */
    private class Relay(private val targetPort: Int) : Closeable {
        private val server = ServerSocket().apply { bind(InetSocketAddress("localhost", 0)) }
        val port: Int get() = server.localPort

        @Volatile private var forwardClientToTarget = true
        @Volatile private var forwardTargetToClient = true
        private val openSockets = Collections.synchronizedList(mutableListOf<Socket>())

        init {
            thread(isDaemon = true, name = "relay-accept") {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (e: Throwable) { break }
                    val target = try { Socket("localhost", targetPort) } catch (e: Throwable) {
                        runCatching { client.close() }; continue
                    }
                    openSockets.add(client); openSockets.add(target)
                    // client -> target: pausing it wedges dadb's writes (the peer stops draining).
                    pump(client, target) { forwardClientToTarget }
                    // target -> client: pausing it wedges dadb's reads (responses never arrive, but
                    // the connection stays open, so SO_TIMEOUT fires rather than hitting EOF).
                    pump(target, client) { forwardTargetToClient }
                }
            }
        }

        private fun pump(from: Socket, to: Socket, enabled: () -> Boolean) {
            thread(isDaemon = true, name = "relay-pump") {
                val buf = ByteArray(16 * 1024)
                try {
                    val input = from.getInputStream()
                    val output = to.getOutputStream()
                    while (true) {
                        while (!enabled()) Thread.sleep(20) // don't start a read while paused
                        val n = input.read(buf)
                        if (n < 0) break
                        while (!enabled()) Thread.sleep(20) // don't forward a read that landed during a pause
                        output.write(buf, 0, n)
                        output.flush()
                    }
                } catch (ignore: Throwable) {
                }
            }
        }

        fun wedgeWrites() { forwardClientToTarget = false }
        fun unwedgeWrites() { forwardClientToTarget = true }
        fun wedgeReads() { forwardTargetToClient = false }

        override fun close() {
            runCatching { server.close() }
            synchronized(openSockets) { openSockets.forEach { runCatching { it.close() } } }
        }
    }

    @Test
    fun `a wedged write fails fast with AdbTimeoutException and the connection recovers`() {
        Relay(targetPort = 5555).use { relay ->
            // writeTimeoutMillis is the internal test seam; production always uses WRITE_TIMEOUT_MILLIS.
            val dadb = DadbImpl(
                host = "localhost",
                port = relay.port,
                keyPair = AdbKeyPair.readDefault(),
                connectTimeout = 5000,
                socketTimeout = 5000,
                writeTimeoutMillis = 1000,
            )
            try {
                // Real handshake + op, through the relay, against real adbd.
                assertThat(dadb.shell("echo warmup").allOutput.trim()).isEqualTo("warmup")

                // Wedge: relay stops draining the client side, so a large write stalls.
                relay.wedgeWrites()
                val bigCommand = "x".repeat(32 * 1024 * 1024) // overflows the OS socket buffers (incl. larger Linux defaults)
                // okio's write timeout fires on the stalled write and surfaces as a typed timeout,
                // not a raw SocketTimeoutException.
                assertThrows<AdbTimeoutException> {
                    assertTimeoutPreemptively(Duration.ofSeconds(8)) { dadb.shell(bigCommand) }
                }

                // The timed-out write closed the socket; once forwarding resumes, the next op rebuilds.
                relay.unwedgeWrites()
                assertThat(dadb.shell("echo recovered").allOutput.trim()).isEqualTo("recovered")
            } finally {
                runCatching { dadb.close() }
            }
        }
    }

    @Test
    fun `a wedged read is bounded by socketTimeout`() {
        Relay(targetPort = 5555).use { relay ->
            val dadb = Dadb.create("localhost", relay.port, connectTimeout = 5000, socketTimeout = 1000)
            try {
                // Real handshake + op, through the relay, against real adbd.
                assertThat(dadb.shell("echo warmup").allOutput.trim()).isEqualTo("warmup")

                // Wedge: adbd's responses stop reaching dadb, so the next op's read blocks until
                // SO_TIMEOUT fires (the connection stays open, so this is a timeout, not an EOF).
                relay.wedgeReads()
                // SO_TIMEOUT trips the read and surfaces as a typed timeout.
                assertThrows<AdbTimeoutException> {
                    assertTimeoutPreemptively(Duration.ofSeconds(8)) { dadb.shell("echo hi") }
                }
            } finally {
                runCatching { dadb.close() }
            }
        }
    }
}
