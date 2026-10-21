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

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AsdbBufferReaderTest {

    @Test
    fun readsAllSections() {
        val data = batch {
            int(1)
            int(1)
            string("Ljava/lang/Object;")
            int(1)
            int(1)
            int(1)
            string("toString")
            string("Object.java")
            int(1)
            int(1)
            int(1)
            int(1)
            int(7)
            int(1)
            int(1)
            long(42L)
            string("main")
            int(1)
            int(1)
            long(64L)
            int(0)
            int(1)
            int(1)
            long(1_700_000_000_000_000_000L)
            long(3L)
        }

        val batch = AsdbBufferReader.read(data)

        assertEquals(listOf(NativeClass(1, "Ljava/lang/Object;")), batch.classes)
        assertEquals(listOf(NativeMethod(1, 1, "toString", "Object.java")), batch.methods)
        assertEquals(listOf(NativeStack(1, listOf(NativeFrame(1, 7)))), batch.stacks)
        assertEquals(listOf(NativeThread(1, "main")), batch.threads)
        assertEquals(
            listOf(NativeAllocation(1, 64L, 0, 1, 1, 1_700_000_000_000_000_000L)),
            batch.allocations,
        )
        assertEquals(3L, batch.dropped)
    }

    @Test
    fun readsEmptyBatch() {
        val data = batch {
            repeat(5) { int(0) }
            long(0L)
        }

        val batch = AsdbBufferReader.read(data)

        assertEquals(0, batch.classes.size)
        assertEquals(0, batch.methods.size)
        assertEquals(0, batch.stacks.size)
        assertEquals(0, batch.threads.size)
        assertEquals(0, batch.allocations.size)
        assertEquals(0L, batch.dropped)
    }

    @Test
    fun rejectsTruncatedBuffer() {
        val data = batch { int(1) }

        val error = assertThrows(IllegalArgumentException::class.java) {
            AsdbBufferReader.read(data)
        }

        assertEquals("Truncated native allocation buffer.", error.message)
    }

    @Test
    fun rejectsNegativeCount() {
        val data = batch { int(-1) }

        val error = assertThrows(IllegalArgumentException::class.java) {
            AsdbBufferReader.read(data)
        }

        assertEquals("Invalid class count: -1.", error.message)
    }

    @Test
    fun rejectsStringLongerThanBuffer() {
        val data = batch {
            int(1)
            int(1)
            int(100)
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            AsdbBufferReader.read(data)
        }

        assertEquals("Truncated native allocation buffer: string length 100.", error.message)
    }

    private fun batch(block: LittleEndianWriter.() -> Unit): ByteArray =
        LittleEndianWriter().apply(block).toByteArray()

    private class LittleEndianWriter {
        private val out = ByteArrayOutputStream()

        fun int(value: Int) {
            repeat(4) { out.write((value ushr (it * 8)) and 0xff) }
        }

        fun long(value: Long) {
            repeat(8) { out.write(((value ushr (it * 8)) and 0xff).toInt()) }
        }

        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            int(bytes.size)
            out.write(bytes)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
