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

internal class AsdbRow(
    @JvmField val kind: Int,
    @JvmField val timestampNs: Long,
    @JvmField val data: ByteArray,
)

/**
 * Turns native batches into the protobuf payloads stored in `UnifiedEventsTable`.
 *
 * This class is deliberately free of Android dependencies so the same encoding can be used by
 * the on-device writer and the host-side startup capture converter.
 */
internal class AsdbRows(private val contextTimestampNs: Long? = null) {

    private data class MethodInfo(
        @JvmField val classId: Int,
        @JvmField val name: String,
        @JvmField val file: String,
    )

    private data class Frame(@JvmField val methodId: Int, @JvmField val line: Int)

    private val classes = LinkedHashMap<Int, String>()
    private val methods = LinkedHashMap<Int, MethodInfo>()
    private val stacks = LinkedHashMap<Int, List<Frame>>()
    private val threads = LinkedHashMap<Int, String>()

    fun consume(batch: NativeBatch): List<AsdbRow> {
        batch.classes.forEach { classes[it.id] = it.name }
        batch.methods.forEach { methods[it.id] = MethodInfo(it.classId, it.name, it.file) }
        batch.stacks.forEach { stack ->
            stacks[stack.id] = stack.frames.map { Frame(it.methodId, it.line) }
        }
        batch.threads.forEach { threads[it.id] = it.name }
        return writeEvents(batch.allocations)
    }

    fun finish(): AsdbRow? = writeContexts()

    private fun writeContexts(): AsdbRow? {
        if (classes.isEmpty() && methods.isEmpty() && stacks.isEmpty() && threads.isEmpty()) {
            return null
        }

        val timestampNs = contextTimestampNs ?: nowNs()
        val batch = ProtoWriter()
        batch.long(1, timestampNs)

        for ((id, name) in classes) {
            val value = ProtoWriter()
            value.int(1, id)
            value.string(2, name)
            batch.bytes(2, value.toByteArray())
        }
        for ((id, method) in methods) {
            val value = ProtoWriter()
            value.int(1, id)
            value.string(2, classes[method.classId] ?: "")
            value.string(3, method.name)
            value.string(4, method.file)
            batch.bytes(3, value.toByteArray())
        }
        for ((id, frames) in stacks) {
            val encoded = ProtoWriter()
            for ((methodId, line) in frames) {
                val value = ProtoWriter()
                value.int(1, methodId)
                value.int(2, line)
                encoded.bytes(1, value.toByteArray())
            }
            val value = ProtoWriter()
            value.int(1, id)
            value.bytes(3, encoded.toByteArray())
            batch.bytes(4, value.toByteArray())
        }
        for ((id, name) in threads) {
            val value = ProtoWriter()
            value.int(2, id)
            value.string(3, name)
            batch.bytes(5, value.toByteArray())
        }

        val contexts = ProtoWriter()
        contexts.bytes(1, batch.toByteArray())

        val event = ProtoWriter()
        event.bytes(408, contexts.toByteArray())
        return AsdbRow(408, timestampNs, event.toByteArray())
    }

    private fun writeEvents(allocations: List<NativeAllocation>): List<AsdbRow> {
        if (allocations.isEmpty()) {
            return emptyList()
        }
        return allocations.chunked(MAX_EVENTS_PER_ROW).map { chunk ->
            val batch = ProtoWriter()
            batch.long(1, chunk.first().timestampNs)
            for ((classId, size, length, threadId, stackId) in chunk) {
                val value = ProtoWriter()
                value.int(2, classId)
                value.long(3, size)
                value.int(4, length)
                value.int(5, threadId)
                if (stackId != 0) {
                    value.int(6, stackId)
                }
                threads[threadId]?.let { value.string(10, it) }

                val event = ProtoWriter()
                event.bytes(4, value.toByteArray())
                batch.bytes(2, event.toByteArray())
            }

            val events = ProtoWriter()
            events.bytes(1, batch.toByteArray())

            val event = ProtoWriter()
            event.bytes(409, events.toByteArray())
            AsdbRow(409, chunk.first().timestampNs, event.toByteArray())
        }
    }

    private fun nowNs(): Long = System.currentTimeMillis() * 1_000_000L

    private companion object {
        const val MAX_EVENTS_PER_ROW = 20_000
    }
}
