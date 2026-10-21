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

import android.content.Context
import hu.oandras.memoryprofiling.internal.AllocationCounters
import hu.oandras.memoryprofiling.internal.AsdbBackend
import hu.oandras.memoryprofiling.internal.HeapprofdBackend
import hu.oandras.memoryprofiling.internal.HprofDump

/**
 * ART heap profiling.
 *
 * The no-argument `.asdb` entry points must be called from an instrumentation test process.
 * Prefer them in tests: artifacts are reported to the test runner as additional test output.
 * The `Context` overloads also work in any debuggable application.
 */
object ArtHeapProfiler {

    /**
     * Whether Perfetto `heapprofd` can sample [heap] in the current test process.
     *
     * Requires a test APK that is `profileable` or `debuggable`. The ART heap additionally requires
     * Android 12 (API 31) or newer; the native heap works from Android 10 (API 29).
     */
    fun isHeapprofdSupported(heap: Heap = Heap.ART): Boolean = HeapprofdBackend.isSupported(heap)

    /**
     * Whether direct `.asdb` allocation capture is available in the current test process.
     *
     * Requires Android 9 (API 28) or newer and a `debuggable` test APK; `profileable` alone is not
     * enough for JVMTI agents.
     */
    fun isAsdbSupported(): Boolean = AsdbBackend.isSupported()

    /**
     * Whether direct `.asdb` allocation capture is available for [context]'s process.
     *
     * Unlike [isAsdbSupported], this can be called outside an instrumentation test.
     */
    fun isAsdbSupported(context: Context): Boolean = AsdbBackend.isSupported(context)

    /**
     * Records Perfetto `heapprofd` samples while [block] runs.
     *
     * `heapprofd` samples allocations, so small objects may be underrepresented. Lower
     * [HeapprofdConfig.samplingIntervalBytes] for better coverage at the cost of overhead.
     *
     * Not reentrant, and must not be combined with `BenchmarkRule` or other Perfetto recording in
     * the same test method.
     *
     * @throws IllegalStateException if [config] requests a heap that the current process or API
     * level cannot profile.
     */
    fun record(
        label: String,
        config: HeapprofdConfig = HeapprofdConfig.artHeap(),
        block: () -> Unit,
    ): ProfileResult = HeapprofdBackend.record(label, config, block)

    /**
     * Measures exact process-wide ART allocation totals while [block] runs.
     *
     * Counters are global to the process, so the test harness allocations are included.
     */
    fun measureCounters(block: () -> Unit): CounterResult = AllocationCounters.measure(block)

    /**
     * Records every (or every Nth) ART allocation with its call stack while [block] runs, and
     * writes an Android Studio memory-profiler `.asdb` database on the device.
     *
     * This uses a JVMTI agent, so the test APK must be `debuggable`; it must not be combined with
     * `BenchmarkRule` timing runs. Prefer [record] when sampling is acceptable, because full
     * tracking unwinds a stack for every allocation.
     *
     * @throws IllegalStateException if the process is not debuggable or the agent cannot attach.
     */
    @androidx.annotation.RequiresApi(28)
    fun recordAsdb(
        label: String,
        config: AsdbConfig = AsdbConfig(),
        block: () -> Unit,
    ): ProfileResult = AsdbBackend.record(label, config, block)

    /**
     * Like [recordAsdb], but writes into [context]'s internal files directory and does not require
     * an instrumentation test. The file is returned in [ProfileResult.path] but is not reported to
     * a test runner.
     *
     * The application must still be `debuggable` for the JVMTI agent to attach.
     */
    @androidx.annotation.RequiresApi(28)
    fun recordAsdb(
        context: Context,
        label: String,
        config: AsdbConfig = AsdbConfig(),
        block: () -> Unit,
    ): ProfileResult = AsdbBackend.record(context, label, config, block)

    /**
     * Writes an HPROF heap dump of the current process.
     *
     * HPROF contains only objects that are alive at dump time; short-lived allocations are not
     * included. Use [record] for allocation call sites.
     */
    fun dumpHprof(label: String): ProfileResult = HprofDump.dump(label)
}
