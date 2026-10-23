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

package hu.oandras.memoryprofiling.startup.converter

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRawReaderTest {

    @Test
    fun readsHeaderAndBatch() {
        val bytes = rawCapture()
        val reader = StartupRawReader(DataInputStream(ByteArrayInputStream(bytes)))

        val header = reader.readHeader()
        assertEquals(1, header.version)
        assertEquals(1, header.sampleEvery)
        assertEquals(32, header.stackDepth)
        assertEquals(1234, header.processId)
        assertEquals(1_700_000_000_000_000_000L, header.startTimeNs)

        val batch = reader.nextBatch()!!
        assertEquals(1, batch.classes.size)
        assertEquals(1, batch.allocations.size)
        assertEquals(1_700_000_000_000_000_000L, batch.allocations.single().timestampNs)
        assertEquals(null, reader.nextBatch())
        assertTrue(reader.ended)
    }

    @Test
    fun reportsMissingEndMarker() {
        val bytes = rawCapture(includeEndMarker = false)
        val reader = StartupRawReader(DataInputStream(ByteArrayInputStream(bytes)))

        reader.readHeader()
        reader.nextBatch()

        assertEquals(null, reader.nextBatch())
        assertFalse(reader.ended)
    }

    @Test
    fun rejectsForeignFile() {
        val reader = StartupRawReader(
            DataInputStream(ByteArrayInputStream("not a capture".toByteArray())),
        )

        val error = assertThrows(StartupRawException::class.java) { reader.readHeader() }
        assertEquals("The file is not a startup allocation capture.", error.message)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val bytes = rawCapture(version = 2)
        val reader = StartupRawReader(DataInputStream(ByteArrayInputStream(bytes)))

        val error = assertThrows(StartupRawException::class.java) { reader.readHeader() }
        assertEquals("Unsupported raw capture version: 2.", error.message)
    }

    @Test
    fun stopsAtTruncatedBatch() {
        val bytes = rawCapture(includeEndMarker = false) +
            LittleEndianWriter().apply { int(100) }.toByteArray()
        val reader = StartupRawReader(DataInputStream(ByteArrayInputStream(bytes)))
        reader.readHeader()
        reader.nextBatch()

        assertEquals(null, reader.nextBatch())
        assertFalse(reader.ended)
    }

    private fun rawCapture(
        version: Int = 1,
        includeEndMarker: Boolean = true,
    ): ByteArray {
        val writer = LittleEndianWriter()
        writer.raw(StartupRawReader.MAGIC)
        writer.int(version)
        writer.int(1)
        writer.int(32)
        writer.int(1234)
        writer.long(1_700_000_000_000_000_000L)
        writer.int(0)

        val batch = LittleEndianWriter().apply {
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
        }.toByteArray()
        writer.int(batch.size)
        writer.raw(batch)
        if (includeEndMarker) {
            writer.int(0)
        }
        return writer.toByteArray()
    }
}

internal class LittleEndianWriter {
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

    fun raw(bytes: ByteArray) {
        out.write(bytes)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
