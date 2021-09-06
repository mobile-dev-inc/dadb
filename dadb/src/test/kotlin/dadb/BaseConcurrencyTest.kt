package dadb

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val EXECUTOR = Executors.newCachedThreadPool()

internal abstract class BaseConcurrencyTest {

    private val futures = ArrayList<CompletableFuture<*>>()

    protected fun launch(count: Int = 0, block: () -> Unit): List<CompletableFuture<Void>> {
        return (0 until count).map {
            CompletableFuture.runAsync(block, EXECUTOR).also { futures.add(it) }
        }
    }

    protected fun CompletableFuture<*>.waitFor() {
        get(1000, TimeUnit.MILLISECONDS)
    }

    protected fun waitForAll() {
        CompletableFuture.allOf(*futures.toTypedArray()).get(5000, TimeUnit.MILLISECONDS)
    }
}