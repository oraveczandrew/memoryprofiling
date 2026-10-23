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

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class NativeClass(@JvmField val id: Int, @JvmField val name: String)

internal data class NativeMethod(
    @JvmField val id: Int,
    @JvmField val classId: Int,
    @JvmField val name: String,
    @JvmField val file: String,
)

internal data class NativeFrame(@JvmField val methodId: Int, @JvmField val line: Int)

internal data class NativeStack(@JvmField val id: Int, @JvmField val frames: List<NativeFrame>)

internal data class NativeThread(@JvmField val id: Int, @JvmField val name: String)

internal data class NativeAllocation(
    @JvmField val classId: Int,
    @JvmField val size: Long,
    @JvmField val length: Int,
    @JvmField val threadId: Int,
    @JvmField val stackId: Int,
    @JvmField val timestampNs: Long,
)

internal data class NativeBatch(
    @JvmField val classes: List<NativeClass>,
    @JvmField val methods: List<NativeMethod>,
    @JvmField val stacks: List<NativeStack>,
    @JvmField val threads: List<NativeThread>,
    @JvmField val allocations: List<NativeAllocation>,
    @JvmField val dropped: Long,
)

internal object AsdbBufferReader {

    fun read(data: ByteArray): NativeBatch {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            val classes = readClasses(buffer)
            val methods = readMethods(buffer)
            val stacks = readStacks(buffer)
            val threads = readThreads(buffer)
            val allocations = readAllocations(buffer)
            if (buffer.remaining() < Long.SIZE_BYTES) {
                throw IllegalArgumentException("Truncated native allocation buffer: missing drop count.")
            }
            NativeBatch(
                classes = classes,
                methods = methods,
                stacks = stacks,
                threads = threads,
                allocations = allocations,
                dropped = buffer.long,
            )
        } catch (e: BufferUnderflowException) {
            throw IllegalArgumentException("Truncated native allocation buffer.", e)
        }
    }

    private fun readClasses(buffer: ByteBuffer): List<NativeClass> {
        val count = readCount(buffer, "class")
        return List(count) {
            NativeClass(id = buffer.int, name = buffer.readString())
        }
    }

    private fun readMethods(buffer: ByteBuffer): List<NativeMethod> {
        val count = readCount(buffer, "method")
        return List(count) {
            NativeMethod(
                id = buffer.int,
                classId = buffer.int,
                name = buffer.readString(),
                file = buffer.readString(),
            )
        }
    }

    private fun readStacks(buffer: ByteBuffer): List<NativeStack> {
        val count = readCount(buffer, "stack")
        return List(count) {
            val id = buffer.int
            val frameCount = readCount(buffer, "frame")
            val frames = List(frameCount) { NativeFrame(methodId = buffer.int, line = buffer.int) }
            NativeStack(id = id, frames = frames)
        }
    }

    private fun readThreads(buffer: ByteBuffer): List<NativeThread> {
        val count = readCount(buffer, "thread")
        return List(count) {
            val id = buffer.int
            buffer.long
            NativeThread(id = id, name = buffer.readString())
        }
    }

    private fun readAllocations(buffer: ByteBuffer): List<NativeAllocation> {
        val count = readCount(buffer, "allocation")
        return List(count) {
            NativeAllocation(
                classId = buffer.int,
                size = buffer.long,
                length = buffer.int,
                threadId = buffer.int,
                stackId = buffer.int,
                timestampNs = buffer.long,
            )
        }
    }

    private fun readCount(buffer: ByteBuffer, name: String): Int {
        if (buffer.remaining() < Int.SIZE_BYTES) {
            throw IllegalArgumentException("Truncated native allocation buffer: missing $name count.")
        }
        val count = buffer.int
        if (count < 0) {
            throw IllegalArgumentException("Invalid $name count: $count.")
        }
        return count
    }

    private fun ByteBuffer.readString(): String {
        val length = readCount(this, "string length")
        if (length > remaining()) {
            throw IllegalArgumentException("Truncated native allocation buffer: string length $length.")
        }
        val bytes = ByteArray(length)
        get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
