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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoWriterTest {

    @Test
    fun writesVarintAndLengthDelimitedFields() {
        val writer = ProtoWriter()
        writer.int(1, 1)
        writer.string(2, "hi")

        assertArrayEquals(
            byteArrayOf(0x08, 0x01, 0x12, 0x02, 'h'.code.toByte(), 'i'.code.toByte()),
            writer.toByteArray(),
        )
    }

    @Test
    fun skipsZeroValues() {
        val writer = ProtoWriter()
        writer.int(1, 0)
        writer.long(2, 0L)

        assertEquals(0, writer.toByteArray().size)
    }

    @Test
    fun writesMultiByteVarint() {
        val writer = ProtoWriter()
        writer.int(1, 300)

        assertArrayEquals(byteArrayOf(0x08, 0xAC.toByte(), 0x02), writer.toByteArray())
    }

    @Test
    fun writesLargeLongVarint() {
        val writer = ProtoWriter()
        writer.long(1, Long.MAX_VALUE)

        val bytes = writer.toByteArray()
        assertEquals(10, bytes.size)
        assertEquals(0x08, bytes[0].toInt())
        assertEquals(0x7F, bytes[9].toInt())
    }
}
