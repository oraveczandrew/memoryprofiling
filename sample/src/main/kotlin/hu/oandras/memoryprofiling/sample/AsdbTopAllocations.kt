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

package hu.oandras.memoryprofiling.sample

import android.database.sqlite.SQLiteDatabase
import java.io.File

data class AllocationCount(@JvmField val className: String, @JvmField val count: Int)

object AsdbTopAllocations {

    fun read(file: File, limit: Int = 10): List<AllocationCount> {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val classNames = readClassNames(db)
            val counts = readAllocationCounts(db)
            return counts.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { (classId, count) ->
                    AllocationCount(classNames[classId] ?: "class#$classId", count)
                }
        }
    }

    private fun readClassNames(db: SQLiteDatabase): Map<Int, String> {
        val names = HashMap<Int, String>()
        db.rawQuery(
            "SELECT Data FROM UnifiedEventsTable WHERE Kind = 408",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val contexts = cursor.getBlob(0).field(408) ?: continue
                val batch = contexts.field(1) ?: continue
                batch.forEachField { field, _, bytes ->
                    if (field != 2 || bytes == null) return@forEachField
                    val id = bytes.varint(1)?.toInt() ?: return@forEachField
                    val name = bytes.string(2) ?: return@forEachField
                    names[id] = name
                }
            }
        }
        return names
    }

    private fun readAllocationCounts(db: SQLiteDatabase): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        db.rawQuery(
            "SELECT Data FROM UnifiedEventsTable WHERE Kind = 409",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val events = cursor.getBlob(0).field(409) ?: continue
                val batch = events.field(1) ?: continue
                batch.forEachField { field, _, bytes ->
                    if (field != 2 || bytes == null) return@forEachField
                    val value = bytes.field(4) ?: return@forEachField
                    val classId = value.varint(2)?.toInt() ?: return@forEachField
                    counts[classId] = (counts[classId] ?: 0) + 1
                }
            }
        }
        return counts
    }

    private fun ByteArray.field(target: Int): ByteArray? {
        var result: ByteArray? = null
        forEachField { field, _, bytes ->
            if (field == target && bytes != null) result = bytes
        }
        return result
    }

    private fun ByteArray.varint(target: Int): Long? {
        var result: Long? = null
        forEachField { field, value, _ ->
            if (field == target && value != null) result = value
        }
        return result
    }

    private fun ByteArray.string(target: Int): String? = field(target)?.toString(Charsets.UTF_8)

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
                else -> error("Unsupported protobuf wire type in field $field")
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
