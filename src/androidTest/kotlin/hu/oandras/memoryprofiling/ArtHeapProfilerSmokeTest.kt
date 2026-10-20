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

package hu.oandras.memoryprofiling

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.RequiresDevice
import androidx.test.filters.SdkSuppress
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class ArtHeapProfilerSmokeTest {

    @Test
    fun countersRecordAllocations() {
        val result = ArtHeapProfiler.measureCounters {
            consume(allocate(allocations = 512, size = 1024))
        }

        assertTrue(result.allocatedCount > 0L)
        assertTrue(result.allocatedBytes > 0L)
        assertTrue(result.durationNanos > 0L)
    }

    @RequiresDevice
    @SdkSuppress(minSdkVersion = 31)
    @Test
    fun heapprofdRecordsTrace() {
        val result = ArtHeapProfiler.record(
            label = "smoke",
            config = HeapprofdConfig(samplingIntervalBytes = 512),
        ) {
            consume(allocate(allocations = 2048, size = 4096))
        }

        val trace = File(result.path)
        assertTrue("Missing heapprofd trace at ${result.path}", trace.isFile)
        assertTrue("Empty heapprofd trace at ${result.path}", trace.length() > 0L)
    }

    @RequiresDevice
    @SdkSuppress(minSdkVersion = 29)
    @Test
    fun heapprofdRecordsNativeTrace() {
        val result = ArtHeapProfiler.record(
            label = "smoke-native",
            config = HeapprofdConfig(
                heaps = setOf(Heap.NATIVE),
                samplingIntervalBytes = 512,
            ),
        ) {
            consume(allocate(allocations = 2048, size = 4096))
        }

        val trace = File(result.path)
        assertTrue("Missing heapprofd trace at ${result.path}", trace.isFile)
        assertTrue("Empty heapprofd trace at ${result.path}", trace.length() > 0L)
    }

    @Test
    fun hprofDumpContainsHeader() {
        val result = ArtHeapProfiler.dumpHprof("smoke")

        val header = ByteArray(HPROF_HEADER.size)
        DataInputStream(FileInputStream(result.path)).use { it.readFully(header) }

        assertTrue(header.contentEquals(HPROF_HEADER))
    }

    private fun allocate(allocations: Int, size: Int): List<ByteArray> =
        ArrayList<ByteArray>(allocations).apply {
            repeat(allocations) { add(ByteArray(size)) }
        }

    private fun consume(value: Any?) {
        checkNotNull(value)
    }

    private companion object {
        val HPROF_HEADER: ByteArray = "JAVA PROFILE 1.0.3".toByteArray(Charsets.US_ASCII)
    }
}
