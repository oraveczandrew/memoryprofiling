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

import hu.oandras.memoryprofiling.internal.AsdbBufferReader
import hu.oandras.memoryprofiling.internal.NativeBatch
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

internal class StartupRawException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

internal data class StartupRawHeader(
    @JvmField val version: Int,
    @JvmField val sampleEvery: Int,
    @JvmField val stackDepth: Int,
    @JvmField val processId: Int,
    @JvmField val startTimeNs: Long,
    @JvmField val flags: Int,
)

internal data class StartupDoneMarker(
    @JvmField val version: Int,
    @JvmField val processId: Int,
    @JvmField val startTimeNs: Long,
    @JvmField val endTimeNs: Long,
    @JvmField val dropped: Long,
)

internal class StartupRawReader(private val input: DataInputStream) {

    var ended: Boolean = false
        private set

    fun readHeader(): StartupRawHeader {
        val magic = ByteArray(MAGIC.size)
        try {
            input.readFully(magic)
        } catch (e: EOFException) {
            throw StartupRawException("The raw capture is too short to contain a header.", e)
        }
        if (!magic.contentEquals(MAGIC)) {
            throw StartupRawException("The file is not a startup allocation capture.")
        }
        val version = input.readLittleEndianInt()
        if (version != VERSION) {
            throw StartupRawException("Unsupported raw capture version: $version.")
        }
        return StartupRawHeader(
            version = version,
            sampleEvery = input.readLittleEndianInt(),
            stackDepth = input.readLittleEndianInt(),
            processId = input.readLittleEndianInt(),
            startTimeNs = input.readLittleEndianLong(),
            flags = input.readLittleEndianInt(),
        )
    }

    /** Returns the next batch, or `null` when the end marker or end of file is reached. */
    fun nextBatch(): NativeBatch? {
        if (ended) {
            return null
        }
        val length = try {
            input.readLittleEndianInt()
        } catch (e: EOFException) {
            return null
        }
        if (length == 0) {
            ended = true
            return null
        }
        if (length < 0) {
            throw StartupRawException("Invalid batch length: ${Integer.toUnsignedString(length)}.")
        }
        val payload = ByteArray(length)
        try {
            input.readFully(payload)
        } catch (e: EOFException) {
            return null
        } catch (e: IOException) {
            throw StartupRawException("Unable to read the raw capture.", e)
        }
        return try {
            AsdbBufferReader.read(payload)
        } catch (e: IllegalArgumentException) {
            throw StartupRawException(e.message ?: "Malformed allocation batch.", e)
        }
    }

    companion object {
        val MAGIC = "AMPSRAW1".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1

        fun open(file: File): StartupRawReader =
            StartupRawReader(DataInputStream(file.inputStream().buffered()))

        fun readDoneMarker(file: File): StartupDoneMarker? {
            if (!file.isFile) {
                return null
            }
            val values = LinkedHashMap<String, String>()
            file.forEachLine { line ->
                val separator = line.indexOf('=')
                if (separator > 0) {
                    values[line.substring(0, separator)] = line.substring(separator + 1)
                }
            }
            return StartupDoneMarker(
                version = values["version"]?.toIntOrNull() ?: return null,
                processId = values["pid"]?.toIntOrNull() ?: 0,
                startTimeNs = values["startNs"]?.toLongOrNull() ?: return null,
                endTimeNs = values["endNs"]?.toLongOrNull() ?: return null,
                dropped = values["dropped"]?.toLongOrNull() ?: 0,
            )
        }
    }
}

private fun DataInputStream.readLittleEndianInt(): Int = Integer.reverseBytes(readInt())

private fun DataInputStream.readLittleEndianLong(): Long = java.lang.Long.reverseBytes(readLong())
