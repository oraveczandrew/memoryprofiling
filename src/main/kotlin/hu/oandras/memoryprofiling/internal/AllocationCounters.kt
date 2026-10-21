/*
 *    Copyright 2026 András Oravecz <info@oandras.hu>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package hu.oandras.memoryprofiling.internal

import android.os.Debug
import hu.oandras.memoryprofiling.CounterResult

internal object AllocationCounters {

    fun measure(block: () -> Unit): CounterResult {
        val start = runtimeSnapshot()
        if (start != null) {
            val startNanos = System.nanoTime()
            block()
            val durationNanos = System.nanoTime() - startNanos
            val end = checkNotNull(runtimeSnapshot())
            return CounterResult(
                allocatedCount = end.count - start.count,
                allocatedBytes = end.bytes - start.bytes,
                durationNanos = durationNanos,
            )
        }
        return legacyMeasure(block)
    }

    private fun runtimeSnapshot(): Snapshot? {
        val objects = Debug.getRuntimeStat("art.gc.objects-allocated")?.toLongOrNull() ?: return null
        val bytes = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: return null
        return Snapshot(objects, bytes)
    }

    @Suppress("DEPRECATION")
    private fun legacyMeasure(block: () -> Unit): CounterResult {
        Debug.startAllocCounting()
        try {
            Debug.resetGlobalAllocCount()
            Debug.resetGlobalAllocSize()

            val startNanos = System.nanoTime()
            block()
            val durationNanos = System.nanoTime() - startNanos

            return CounterResult(
                allocatedCount = Debug.getGlobalAllocCount().toLong(),
                allocatedBytes = Debug.getGlobalAllocSize().toLong(),
                durationNanos = durationNanos,
            )
        } finally {
            Debug.stopAllocCounting()
        }
    }

    private data class Snapshot(@JvmField val count: Long, @JvmField val bytes: Long)
}
