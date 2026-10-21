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

    private fun ByteArray.field(target: Int): ByteArray? {
        var result: ByteArray? = null
        forEachField { field, _, bytes -> if (field == target) result = bytes }
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
