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

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class AsdbWriter(private val file: File) : Closeable {

    private data class MethodInfo(val classId: Int, val name: String, val file: String)

    private data class Frame(val methodId: Int, val line: Int)

    private data class Allocation(
        val classId: Int,
        val size: Long,
        val length: Int,
        val threadId: Int,
        val stackId: Int,
        val timestampNs: Long,
    )

    private val classes = LinkedHashMap<Int, String>()
    private val methods = LinkedHashMap<Int, MethodInfo>()
    private val stacks = LinkedHashMap<Int, List<Frame>>()
    private val threads = LinkedHashMap<Int, String>()

    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)

    init {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS UnifiedEventsTable (" +
                "StreamId INTEGER, ProcessId INTEGER, GroupId INTEGER, Kind INTEGER, " +
                "CommandId INTEGER, Timestamp INTEGER, Data BLOB)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_UnifiedEventsTable_Kind " +
                "ON UnifiedEventsTable(Kind)",
        )
    }

    fun consume(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val classCount = buffer.getInt()
        repeat(classCount) {
            val id = buffer.getInt()
            classes[id] = buffer.readString()
        }
        val methodCount = buffer.getInt()
        repeat(methodCount) {
            val id = buffer.getInt()
            val classId = buffer.getInt()
            val name = buffer.readString()
            val file = buffer.readString()
            methods[id] = MethodInfo(classId, name, file)
        }
        val stackCount = buffer.getInt()
        repeat(stackCount) {
            val id = buffer.getInt()
            val frameCount = buffer.getInt()
            val frames = ArrayList<Frame>(frameCount)
            repeat(frameCount) {
                frames.add(Frame(buffer.getInt(), buffer.getInt()))
            }
            stacks[id] = frames
        }
        val threadCount = buffer.getInt()
        repeat(threadCount) {
            val id = buffer.getInt()
            buffer.getLong()
            threads[id] = buffer.readString()
        }

        val allocationCount = buffer.getInt()
        val allocations = ArrayList<Allocation>(allocationCount)
        repeat(allocationCount) {
            allocations.add(
                Allocation(
                    classId = buffer.getInt(),
                    size = buffer.getLong(),
                    length = buffer.getInt(),
                    threadId = buffer.getInt(),
                    stackId = buffer.getInt(),
                    timestampNs = buffer.getLong(),
                ),
            )
        }
        buffer.getLong()

        writeEvents(allocations)
    }

    fun finish() {
        writeContexts()
        db.close()
    }

    override fun close() {
        if (db.isOpen) {
            db.close()
        }
    }

    private fun writeContexts() {
        if (classes.isEmpty() && methods.isEmpty() && stacks.isEmpty() && threads.isEmpty()) {
            return
        }

        val batch = ProtoWriter()
        batch.long(1, System.currentTimeMillis() * 1_000_000L)

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
            for (frame in frames) {
                val value = ProtoWriter()
                value.int(1, frame.methodId)
                value.int(2, frame.line)
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
        insertRow(408, System.currentTimeMillis() * 1_000_000L, event.toByteArray())
    }

    private fun writeEvents(allocations: List<Allocation>) {
        for (chunk in allocations.chunked(MAX_EVENTS_PER_ROW)) {
            val batch = ProtoWriter()
            batch.long(1, chunk.first().timestampNs)
            for (allocation in chunk) {
                val value = ProtoWriter()
                value.int(2, allocation.classId)
                value.long(3, allocation.size)
                value.int(4, allocation.length)
                value.int(5, allocation.threadId)
                if (allocation.stackId != 0) {
                    value.int(6, allocation.stackId)
                }
                threads[allocation.threadId]?.let { value.string(10, it) }

                val event = ProtoWriter()
                event.bytes(4, value.toByteArray())
                batch.bytes(2, event.toByteArray())
            }

            val events = ProtoWriter()
            events.bytes(1, batch.toByteArray())

            val event = ProtoWriter()
            event.bytes(409, events.toByteArray())
            insertRow(409, chunk.first().timestampNs, event.toByteArray())
        }
    }

    private fun insertRow(kind: Int, timestamp: Long, data: ByteArray) {
        val values = ContentValues()
        values.put("StreamId", 1)
        values.put("ProcessId", Process.myPid())
        values.put("GroupId", 0)
        values.put("Kind", kind)
        values.put("CommandId", 0)
        values.put("Timestamp", timestamp)
        values.put("Data", data)
        db.insertOrThrow("UnifiedEventsTable", null, values)
    }

    private fun ByteBuffer.readString(): String {
        val length = int
        val bytes = ByteArray(length)
        get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private companion object {
        const val MAX_EVENTS_PER_ROW = 20_000
    }
}

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
