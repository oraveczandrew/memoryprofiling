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

import hu.oandras.memoryprofiling.Heap
import hu.oandras.memoryprofiling.HeapprofdConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeapprofdConfigTest {

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyHeapSet() {
        HeapprofdConfig(heaps = emptySet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroSamplingInterval() {
        HeapprofdConfig(samplingIntervalBytes = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPowerOfTwoShmem() {
        HeapprofdConfig(shmemSizeBytes = 12_345)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooSmallShmem() {
        HeapprofdConfig(shmemSizeBytes = 4_096)
    }

    @Test
    fun writesSelectedHeapsAndEscapesProcessName() {
        val proto = heapprofdTextProto(
            processName = "my\"app\\name",
            config = HeapprofdConfig(heaps = setOf(Heap.ART, Heap.NATIVE)),
            includeHeaps = true,
        )

        assertTrue(proto.contains("process_cmdline: \"my\\\"app\\\\name\""))
        assertTrue(proto.contains("heaps: \"com.android.art\""))
        assertTrue(proto.contains("heaps: \"libc.malloc\""))
    }

    @Test
    fun omitsHeapLinesBeforeApi31() {
        val proto = heapprofdTextProto(
            processName = "app",
            config = HeapprofdConfig(heaps = setOf(Heap.NATIVE)),
            includeHeaps = false,
        )

        assertFalse(proto.contains("heaps:"))
    }
}
