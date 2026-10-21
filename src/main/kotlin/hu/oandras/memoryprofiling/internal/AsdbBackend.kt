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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import hu.oandras.memoryprofiling.AsdbConfig
import hu.oandras.memoryprofiling.ProfileKind
import hu.oandras.memoryprofiling.ProfileResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object AsdbBackend {

    private const val POLL_INTERVAL_MS = 25L

    private val captureInProgress = AtomicBoolean(false)

    fun isSupported(): Boolean =
        try {
            isSupported(InstrumentationRegistry.getInstrumentation().targetContext)
        } catch (_: IllegalStateException) {
            false
        }

    fun isSupported(context: Context): Boolean =
        NativeAllocAgent.isAvailable(context) &&
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun record(label: String, config: AsdbConfig, block: () -> Unit): ProfileResult {
        val context = try {
            InstrumentationRegistry.getInstrumentation().targetContext
        } catch (e: IllegalStateException) {
            throw AsdbCaptureException(
                "The no-argument recordAsdb requires an instrumentation test; " +
                    "use the Context overload outside tests.",
                e,
            )
        }
        return record(context, label, config, block)
    }

    fun record(context: Context, label: String, config: AsdbConfig, block: () -> Unit): ProfileResult {
        if (!captureInProgress.compareAndSet(false, true)) {
            throw AsdbCaptureException("Another .asdb capture is already running.")
        }
        try {
            return capture(context, label, config, block)
        } finally {
            captureInProgress.set(false)
        }
    }

    private fun capture(
        context: Context,
        label: String,
        config: AsdbConfig,
        block: () -> Unit,
    ): ProfileResult {
        if (!isSupported(context)) {
            throw AsdbNotSupportedException(unsupportedMessage(context))
        }
        if (config.sampleEvery <= 0) {
            throw AsdbCaptureException("sampleEvery must be at least 1.")
        }
        if (config.stackDepth !in 0..256) {
            throw AsdbCaptureException("stackDepth must be between 0 and 256.")
        }

        NativeAllocAgent.attach(context, config.sampleEvery, config.stackDepth)

        val file = try {
            OutputFiles.createInternal(context, label, "asdb")
        } catch (t: Throwable) {
            throw AsdbCaptureException("Unable to create the .asdb output file.", t)
        }
        val writer = try {
            AsdbWriter(file)
        } catch (t: Throwable) {
            throw AsdbCaptureException("Unable to create the .asdb database.", t)
        }
        val stop = AtomicBoolean(false)
        val writerFailure = AtomicReference<Throwable?>()
        val thread = Thread(
            {
                try {
                    while (!stop.get()) {
                        drainInto(writer)
                        Thread.sleep(POLL_INTERVAL_MS)
                    }
                    drainInto(writer)
                } catch (t: Throwable) {
                    writerFailure.set(t)
                }
            },
            "asdb-writer",
        )

        try {
            NativeAllocAgent.start()
        } catch (t: Throwable) {
            writer.close()
            throw AsdbCaptureException("Unable to start the allocation agent.", t)
        }

        var blockFailure: Throwable? = null
        var finishFailure: Throwable? = null
        thread.start()
        try {
            block()
        } catch (t: Throwable) {
            blockFailure = t
        } finally {
            NativeAllocAgent.stop()
            stop.set(true)
            var interrupted = false
            while (true) {
                try {
                    thread.join()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
            try {
                writer.finish()
            } catch (t: Throwable) {
                finishFailure = t
            }
        }
        blockFailure?.let { throw it }
        finishFailure?.let { throw AsdbCaptureException("Unable to finish the .asdb database.", it) }

        writerFailure.get()?.let { throw AsdbCaptureException("The .asdb writer failed.", it) }
        if (!file.exists() || file.length() == 0L) {
            throw AsdbCaptureException("The .asdb capture was not written to ${file.absolutePath}.")
        }
        val eventCount = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM UnifiedEventsTable WHERE Kind = 409",
                null,
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        }
        if (eventCount == 0L) {
            throw AsdbCaptureException(
                "The .asdb capture contains no allocation events. " +
                    "Allocate at least one object inside the block.",
            )
        }

        try {
            OutputFiles.report(file)
        } catch (t: Throwable) {
            throw AsdbCaptureException("Unable to report the .asdb capture.", t)
        }
        val dropped = NativeAllocAgent.droppedEvents()
        if (dropped > 0L) {
            Log.w(
                "ArtHeapProfiler",
                "The .asdb capture dropped $dropped allocation events because the buffer was full.",
            )
        }
        return ProfileResult(
            label = label,
            path = file.absolutePath,
            kind = ProfileKind.ASDB,
            droppedAllocations = dropped,
        )
    }

    private fun unsupportedMessage(context: Context): String = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P ->
            "Direct .asdb capture requires Android 9 (API 28) or newer."

        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ->
            "Direct .asdb capture requires a debuggable app or test APK."

        else -> "The JVMTI allocation agent is not available on this device."
    }

    private fun drainInto(writer: AsdbWriter) {
        while (true) {
            val data = NativeAllocAgent.drain() ?: return
            writer.consume(data)
        }
    }
}
