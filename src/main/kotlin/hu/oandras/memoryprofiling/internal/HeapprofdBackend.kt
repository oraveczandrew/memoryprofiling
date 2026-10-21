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

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.benchmark.perfetto.ExperimentalPerfettoCaptureApi
import androidx.benchmark.perfetto.PerfettoConfig
import androidx.benchmark.traceprocessor.PerfettoTrace
import androidx.benchmark.traceprocessor.record
import androidx.test.platform.app.InstrumentationRegistry
import hu.oandras.memoryprofiling.Heap
import hu.oandras.memoryprofiling.HeapprofdConfig
import hu.oandras.memoryprofiling.ProfileKind
import hu.oandras.memoryprofiling.ProfileResult
import java.io.File

internal object HeapprofdBackend {

    fun isSupported(heap: Heap): Boolean =
        Build.VERSION.SDK_INT >= minSdkFor(heap) && isProfileable()

    private fun isProfileable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        val applicationInfo: ApplicationInfo =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo

        return applicationInfo.isProfileableByShell ||
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    private fun minSdkFor(heap: Heap): Int =
        when (heap) {
            Heap.ART -> Build.VERSION_CODES.S
            Heap.NATIVE -> Build.VERSION_CODES.Q
        }

    @OptIn(ExperimentalPerfettoCaptureApi::class)
    @SuppressLint("RestrictedApi")
    fun record(
        label: String,
        config: HeapprofdConfig,
        block: () -> Unit,
    ): ProfileResult {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "heapprofd requires Android 10 (API 29) or newer."
        }
        check(config.heaps.none { it == Heap.ART } || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "heapprofd cannot sample the ART heap before Android 12 (API 31)."
        }
        check(isProfileable()) {
            "heapprofd requires a profileable or debuggable test APK."
        }

        val processName = config.processName ?: defaultProcessName()
        var tracePath: String? = null

        PerfettoTrace.record(
            fileLabel = label,
            config = PerfettoConfig.Text(
                heapprofdTextProto(
                    processName = processName,
                    config = config,
                    includeHeaps = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                ),
            ),
            traceCallback = { trace -> tracePath = trace.path },
            block = block,
        )

        val path = checkNotNull(tracePath) { "Perfetto did not produce a trace." }
        OutputFiles.report(File(path))

        return ProfileResult(
            label = label,
            path = path,
            kind = ProfileKind.HEAPPROFD,
        )
    }

    private fun defaultProcessName(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.packageName
}

internal fun heapprofdTextProto(
    processName: String,
    config: HeapprofdConfig,
    includeHeaps: Boolean,
): String =
    buildString {
        appendLine("buffers {")
        appendLine("  size_kb: 65536")
        appendLine("}")
        appendLine("data_sources {")
        appendLine("  config {")
        appendLine("    name: \"android.heapprofd\"")
        appendLine("    target_buffer: 0")
        appendLine("    heapprofd_config {")
        appendLine("      sampling_interval_bytes: ${config.samplingIntervalBytes}")
        appendLine("      shmem_size_bytes: ${config.shmemSizeBytes}")
        appendLine("      block_client: ${config.blockClient}")
        appendLine("      process_cmdline: \"${escapeTextProto(processName)}\"")
        if (includeHeaps) {
            config.heaps.forEach { heap ->
                appendLine("      heaps: \"${heap.perfettoName}\"")
            }
        }
        if (config.dumpIntervalMs > 0) {
            appendLine("      continuous_dump_config {")
            appendLine("        dump_phase_ms: 0")
            appendLine("        dump_interval_ms: ${config.dumpIntervalMs}")
            appendLine("      }")
        }
        appendLine("    }")
        appendLine("  }")
        appendLine("}")
    }

private fun escapeTextProto(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
