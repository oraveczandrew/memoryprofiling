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

internal class ProtoWriter {

    private val out = ByteArrayOutputStream()

    fun int(field: Int, value: Int) {
        if (value != 0) {
            tag(field, WIRE_VARINT)
            varint(value.toLong())
        }
    }

    fun long(field: Int, value: Long) {
        if (value != 0L) {
            tag(field, WIRE_VARINT)
            varint(value)
        }
    }

    fun string(field: Int, value: String) {
        bytes(field, value.toByteArray(Charsets.UTF_8))
    }

    fun bytes(field: Int, value: ByteArray) {
        tag(field, WIRE_BYTES)
        varint(value.size.toLong())
        out.write(value)
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) {
        varint(((field shl 3) or wireType).toLong())
    }

    private fun varint(value: Long) {
        var remaining = value
        while (remaining and 0x7FL.inv() != 0L) {
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        out.write(remaining.toInt())
    }

    private companion object {
        const val WIRE_VARINT = 0
        const val WIRE_BYTES = 2
    }
}
