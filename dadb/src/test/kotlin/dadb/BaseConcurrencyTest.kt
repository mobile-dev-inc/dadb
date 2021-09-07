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

    protected fun waitForAll() {
        CompletableFuture.allOf(*futures.toTypedArray()).get(5000, TimeUnit.MILLISECONDS)
    }
}