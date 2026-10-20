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

/** Heap sampled by Perfetto `heapprofd`. */
enum class Heap(internal val perfettoName: String) {
    /** ART managed heap (`com.android.art`). Requires Android 12 (API 31) or newer. */
    ART("com.android.art"),

    /** Native heap allocated through `malloc` (`libc.malloc`). Requires Android 10 (API 29). */
    NATIVE("libc.malloc"),
}

/**
 * Configuration for a Perfetto `heapprofd` run.
 *
 * @property heaps Heaps to sample. ART is the default. On Android 10 and 11 only [Heap.NATIVE] is
 * available because ART heap profiling was added to `heapprofd` in Android 12.
 * @property samplingIntervalBytes Average number of allocated bytes between samples. Lower values
 * give more complete data at higher overhead. Must be greater than zero.
 * @property shmemSizeBytes Shared memory buffer size. Must be a power of two and at least 8192.
 * @property dumpIntervalMs Interval between heap dumps.
 * @property blockClient Whether to block the target when the shared buffer is full. Blocking keeps
 * the profile complete but slows the target down.
 * @property processName Process to profile. Defaults to the test process package name.
 */
class HeapprofdConfig(
    val heaps: Set<Heap> = setOf(Heap.ART),
    val samplingIntervalBytes: Int = DEFAULT_SAMPLING_INTERVAL_BYTES,
    val shmemSizeBytes: Int = DEFAULT_SHMEM_SIZE_BYTES,
    val dumpIntervalMs: Int = DEFAULT_DUMP_INTERVAL_MS,
    val blockClient: Boolean = true,
    val processName: String? = null,
) {

    init {
        require(heaps.isNotEmpty()) {
            "At least one heap must be sampled."
        }
        require(samplingIntervalBytes > 0) {
            "samplingIntervalBytes must be greater than zero."
        }
        require(shmemSizeBytes >= MIN_SHMEM_SIZE_BYTES) {
            "shmemSizeBytes must be at least $MIN_SHMEM_SIZE_BYTES."
        }
        require(shmemSizeBytes and (shmemSizeBytes - 1) == 0) {
            "shmemSizeBytes must be a power of two."
        }
        require(dumpIntervalMs >= 0) {
            "dumpIntervalMs must not be negative."
        }
    }

    companion object {
        const val DEFAULT_SAMPLING_INTERVAL_BYTES: Int = 2048
        const val DEFAULT_SHMEM_SIZE_BYTES: Int = 8 * 1024 * 1024
        const val DEFAULT_DUMP_INTERVAL_MS: Int = 500
        private const val MIN_SHMEM_SIZE_BYTES: Int = 8192

        /** Samples the ART heap with default settings. */
        fun artHeap(): HeapprofdConfig = HeapprofdConfig()
    }
}
