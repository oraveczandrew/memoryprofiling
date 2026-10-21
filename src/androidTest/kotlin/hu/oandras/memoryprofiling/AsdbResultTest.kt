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

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsdbResultTest {

    @SdkSuppress(maxSdkVersion = 27)
    @Test
    fun unsupportedApiReturnsNotSupportedWithoutRunningBlock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var blockRan = false

        val capture = ArtHeapProfiler.recordAsdb(context, "unsupported") {
            blockRan = true
        }

        assertTrue("Expected NotSupported, got $capture", capture is AsdbCaptureResult.NotSupported)
        assertTrue(!blockRan)
    }

    @SdkSuppress(minSdkVersion = 28)
    @Test
    fun invalidConfigReturnsFailureWithoutRunningBlock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var blockRan = false

        val capture = ArtHeapProfiler.recordAsdb(
            context,
            "invalid",
            AsdbConfig(sampleEvery = 0),
        ) {
            blockRan = true
        }

        assertTrue("Expected Failure, got $capture", capture is AsdbCaptureResult.Failure)
        assertTrue(!blockRan)
    }

    @SdkSuppress(minSdkVersion = 28)
    @Test
    fun emptyCaptureReturnsFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val capture = ArtHeapProfiler.recordAsdb(
            context,
            "empty",
            AsdbConfig(sampleEvery = Int.MAX_VALUE, stackDepth = 0),
        ) {}

        assertTrue("Expected Failure, got $capture", capture is AsdbCaptureResult.Failure)
        assertTrue(
            "Unexpected message: ${(capture as AsdbCaptureResult.Failure).message}",
            capture.message.contains("no allocation events"),
        )
    }

    @SdkSuppress(minSdkVersion = 28)
    @Test
    fun concurrentCaptureReturnsFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicReference<AsdbCaptureResult?>()
        val config = AsdbConfig(sampleEvery = 1, stackDepth = 0)

        val captureThread = thread(name = "first-asdb-capture") {
            first.set(
                ArtHeapProfiler.recordAsdb(context, "first", config) {
                    entered.countDown()
                    release.await()
                    allocate()
                },
            )
        }

        assertTrue("The first capture did not start", entered.await(30, TimeUnit.SECONDS))
        val second = ArtHeapProfiler.recordAsdb(context, "second", config) { allocate() }
        release.countDown()
        captureThread.join(30_000)

        assertTrue("Expected Failure, got $second", second is AsdbCaptureResult.Failure)
        assertTrue(
            "Unexpected message: ${(second as AsdbCaptureResult.Failure).message}",
            second.message.contains("already running"),
        )
        assertTrue("Expected Success, got ${first.get()}", first.get() is AsdbCaptureResult.Success)
    }

    @SdkSuppress(minSdkVersion = 28)
    @Test
    fun blockExceptionPropagatesUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = IllegalStateException("workload failed")

        val thrown = try {
            ArtHeapProfiler.recordAsdb(context, "block-failure") { throw expected }
            null
        } catch (t: Throwable) {
            t
        }

        assertSame(expected, thrown)
    }

    @SdkSuppress(minSdkVersion = 28)
    @Test
    fun overloadsGetDistinctMethodIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val capture = ArtHeapProfiler.recordAsdb(
            context,
            "overloads",
            AsdbConfig(sampleEvery = 1, stackDepth = 8),
        ) {
            overloaded(1)
            overloaded("value")
        }
        assertTrue("Expected Success, got $capture", capture is AsdbCaptureResult.Success)

        val file = File((capture as AsdbCaptureResult.Success).profile.path)
        val methodIds = HashSet<Int>()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT Data FROM UnifiedEventsTable WHERE Kind = 408", null).use { c ->
                while (c.moveToNext()) {
                    val contexts = c.getBlob(0).field(408) ?: continue
                    val batch = contexts.field(1) ?: continue
                    batch.forEachField { field, _, bytes ->
                        if (field == 3 && bytes != null && bytes.string(3) == "overloaded") {
                            bytes.varInt(1)?.let { methodIds.add(it.toInt()) }
                        }
                    }
                }
            }
        }

        assertTrue("Expected two method IDs, got $methodIds", methodIds.size >= 2)
    }

    @SdkSuppress(minSdkVersion = 28)
    @Test
    fun repeatedCaptureDefinesAllReferencedClasses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = AsdbConfig(sampleEvery = 1, stackDepth = 4)

        assertTrue(
            ArtHeapProfiler.recordAsdb(context, "repeat-first", config) { allocate() }
                is AsdbCaptureResult.Success,
        )
        val second = ArtHeapProfiler.recordAsdb(context, "repeat-second", config) { allocate() }
        assertTrue("Expected Success, got $second", second is AsdbCaptureResult.Success)

        val file = File((second as AsdbCaptureResult.Success).profile.path)
        val defined = HashSet<Int>()
        val referenced = HashSet<Int>()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT Data FROM UnifiedEventsTable WHERE Kind = 408", null).use { c ->
                while (c.moveToNext()) {
                    val contexts = c.getBlob(0).field(408) ?: continue
                    val batch = contexts.field(1) ?: continue
                    batch.forEachField { field, _, bytes ->
                        if (field == 2 && bytes != null) {
                            bytes.varInt(1)?.let { defined.add(it.toInt()) }
                        }
                    }
                }
            }
            db.rawQuery("SELECT Data FROM UnifiedEventsTable WHERE Kind = 409", null).use { c ->
                while (c.moveToNext()) {
                    val events = c.getBlob(0).field(409) ?: continue
                    val batch = events.field(1) ?: continue
                    batch.forEachField { field, _, bytes ->
                        if (field == 2 && bytes != null) {
                            val value = bytes.field(4) ?: return@forEachField
                            value.varInt(2)?.let { referenced.add(it.toInt()) }
                        }
                    }
                }
            }
        }

        assertTrue("No allocation events were written", referenced.isNotEmpty())
        assertTrue(
            "Referenced classes missing from contexts: ${referenced - defined}",
            defined.containsAll(referenced),
        )
    }

    private fun allocate() {
        val values = ArrayList<String>(1000)
        repeat(1000) { values.add("value-$it") }
        check(values.size == 1000)
    }

    private fun overloaded(value: Int) {
        check(value == 1)
        allocate()
    }

    private fun overloaded(value: String) {
        check(value == "value")
        allocate()
    }

    private fun ByteArray.field(target: Int): ByteArray? {
        var result: ByteArray? = null
        forEachField { field, _, bytes -> if (field == target) result = bytes }
        return result
    }

    private fun ByteArray.string(target: Int): String? {
        var result: String? = null
        forEachField { field, _, bytes -> if (field == target) result = bytes?.toString(Charsets.UTF_8) }
        return result
    }

    private fun ByteArray.varInt(target: Int): Long? {
        var result: Long? = null
        forEachField { field, value, _ -> if (field == target) result = value }
        return result
    }

    private inline fun ByteArray.forEachField(block: (Int, Long?, ByteArray?) -> Unit) {
        var position = 0
        while (position < size) {
            val (tag, afterTag) = readVarInt(position)
            position = afterTag
            val field = (tag ushr 3).toInt()
            when ((tag and 0x7).toInt()) {
                0 -> {
                    val (value, next) = readVarInt(position)
                    position = next
                    block(field, value, null)
                }
                2 -> {
                    val (length, next) = readVarInt(position)
                    val end = next + length.toInt()
                    block(field, null, copyOfRange(next, end))
                    position = end
                }
                else -> error("Unsupported wire type")
            }
        }
    }

    private fun ByteArray.readVarInt(start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var position = start
        while (true) {
            val byte = this[position++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result to position
            shift += 7
        }
    }
}
