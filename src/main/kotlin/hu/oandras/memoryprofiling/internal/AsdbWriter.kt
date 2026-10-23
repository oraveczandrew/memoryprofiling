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
import java.io.Closeable
import java.io.File

internal class AsdbWriter(file: File) : Closeable {

    private val rows = AsdbRows()

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
        rows.consume(AsdbBufferReader.read(data)).forEach(::insertRow)
    }

    fun finish() {
        rows.finish()?.let(::insertRow)
        db.close()
    }

    override fun close() {
        if (db.isOpen) {
            db.close()
        }
    }

    private fun insertRow(row: AsdbRow) {
        val values = ContentValues()
        values.put("StreamId", 1)
        values.put("ProcessId", Process.myPid())
        values.put("GroupId", 0)
        values.put("Kind", row.kind)
        values.put("CommandId", 0)
        values.put("Timestamp", row.timestampNs)
        values.put("Data", row.data)
        db.insertOrThrow("UnifiedEventsTable", null, values)
    }
}
