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

import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import hu.oandras.memoryprofiling.AsdbConfig
import hu.oandras.memoryprofiling.ProfileKind
import hu.oandras.memoryprofiling.ProfileResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object AsdbBackend {

    private const val POLL_INTERVAL_MS = 25L

    fun isSupported(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return NativeAllocAgent.isAvailable() &&
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    fun record(label: String, config: AsdbConfig, block: () -> Unit): ProfileResult {
        require(config.sampleEvery > 0) { "sampleEvery must be at least 1." }
        require(config.stackDepth in 0..256) { "stackDepth must be between 0 and 256." }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeAllocAgent.attach(context, config.sampleEvery, config.stackDepth)

        val file = OutputFiles.createInternal(label, "asdb")
        val writer = AsdbWriter(file)
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

        NativeAllocAgent.start()
        thread.start()
        try {
            block()
        } finally {
            NativeAllocAgent.stop()
            stop.set(true)
            thread.join()
            writer.finish()
        }

        writerFailure.get()?.let { throw it }
        check(file.exists() && file.length() > 0L) {
            "The .asdb capture was not written to ${file.absolutePath}."
        }

        OutputFiles.report(file)
        return ProfileResult(label = label, path = file.absolutePath, kind = ProfileKind.ASDB)
    }

    private fun drainInto(writer: AsdbWriter) {
        while (true) {
            val data = NativeAllocAgent.drain() ?: return
            writer.consume(data)
        }
    }
}
