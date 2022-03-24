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
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

internal class TcpForwarder(
    private val dadb: Dadb,
    private val hostPort: Int,
    private val targetPort: Int,
) : AutoCloseable {

    private var state: State = State.STOPPED
    private var thread: Thread? = null

    fun start() {
        check(state == State.STOPPED) { "Forwarder is already started at port $hostPort" }

        moveToState(State.STARTING)
        thread = thread {
            try {
                handleForwarding()
            } finally {
                moveToState(State.STOPPED)
            }
        }

        waitFor(10, 5000) {
            state == State.STARTED || state == State.STOPPED
        }
    }

    private fun handleForwarding() {
        val adbStream = dadb.open("tcp:$targetPort")

        val server = ServerSocket(hostPort)

        moveToState(State.STARTED)

        val client = server.accept()

        val readerThread = thread {
            forward(
                client.getInputStream().source(),
                adbStream.sink
            )
        }

        val writerThread = thread {
            forward(
                adbStream.source,
                client.sink().buffer()
            )
        }

        while (!Thread.interrupted()) {
            try {
                Thread.sleep(500)
            } catch (ignored: InterruptedException) {
                break
            }
        }

        readerThread.interrupt()
        writerThread.interrupt()
    }

    override fun close() {
        if (state == State.STOPPED || state == State.STOPPING) {
            return
        }

        moveToState(State.STOPPING)
        thread?.interrupt()
        thread = null

        waitFor(10, 5000) {
            state == State.STOPPED
        }
    }

    private fun forward(source: Source, sink: BufferedSink) {
        try {
            while (!Thread.interrupted()) {
                try {
                    source.read(sink.buffer, 256)
                    sink.flush()
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
