package dadb.forwarding

import dadb.Dadb
import okio.BufferedSink
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

internal class TcpForwarder(
    private val dadb: Dadb,
    private val hostPort: Int,
    private val targetPort: Int,
) : AutoCloseable {

    private var state: State = State.STOPPED
    private var serverThread: Thread? = null
    private var server: ServerSocket? = null
    private var clientExecutor: ExecutorService? = null

    fun start() {
        check(state == State.STOPPED) { "Forwarder is already started at port $hostPort" }

        moveToState(State.STARTING)

        clientExecutor = Executors.newCachedThreadPool()
        serverThread = thread {
            try {
                handleForwarding()
            } finally {
                moveToState(State.STOPPED)
            }
        }

        waitFor(10, 5000) {
            state == State.STARTED
        }
    }

    private fun handleForwarding() {
        val serverRef = ServerSocket(hostPort)
        server = serverRef

        moveToState(State.STARTED)

        while (!Thread.interrupted()) {
            val client = serverRef.accept()

            clientExecutor?.execute {
                val adbStream = dadb.open("tcp:$targetPort")

                val readerThread = thread {
                    forward(
                        client.getInputStream().source(),
                        adbStream.sink
                    )
                }

                try {
                    forward(
                        adbStream.source,
                        client.sink().buffer()
                    )
                } finally {
                    adbStream.close()
                    client.close()

                    readerThread.interrupt()
                }
            }
        }
    }

    override fun close() {
        if (state == State.STOPPED || state == State.STOPPING) {
            return
        }

        // Make sure that we are not stopping the server while it is in a transient state
        // to avoid surprises
        waitFor(10, 5000) {
            state == State.STARTED
        }

        moveToState(State.STOPPING)

        server?.close()
        server = null
        clientExecutor?.shutdown()
        clientExecutor = null
        serverThread?.interrupt()
        serverThread = null

        waitFor(10, 5000) {
            state == State.STOPPED
        }
    }

    private fun forward(source: Source, sink: BufferedSink) {
        try {
            while (!Thread.interrupted()) {
                try {
                    if (source.read(sink.buffer, 256) >= 0) {
                        sink.flush()
                    } else {
                        return
                    }
                } catch (ignored: IOException) {
                    // Do nothing
                }
            }
        } catch (ignored: InterruptedException) {
            // Do nothing
        } catch (ignored: InterruptedIOException) {
            // do nothing
        }
    }

    private fun moveToState(state: State) {
        this.state = state
    }

    private enum class State {
        STARTING,
        STARTED,
        STOPPING,
        STOPPED
    }

    private fun waitFor(intervalMs: Int, timeoutMs: Int, test: () -> Boolean) {
        val start = System.currentTimeMillis()
        var lastCheck = start
        while (!test()) {
            val now = System.currentTimeMillis()
            val timeSinceStart = now - start
            val timeSinceLastCheck = now - lastCheck
            if (timeoutMs in 0..timeSinceStart) {
                throw TimeoutException()
            }
            val sleepTime = intervalMs - timeSinceLastCheck
            if (sleepTime > 0) {
                Thread.sleep(sleepTime)
            }
            lastCheck = System.currentTimeMillis()
        }
    }

}
