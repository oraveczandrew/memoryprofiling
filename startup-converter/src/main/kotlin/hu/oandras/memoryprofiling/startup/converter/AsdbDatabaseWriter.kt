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

import hu.oandras.memoryprofiling.internal.AsdbRow
import java.io.Closeable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

internal class AsdbDatabaseWriter(file: File) : Closeable {

    private val connection: Connection
    private val insert: PreparedStatement

    init {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=DELETE")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute(
                "CREATE TABLE IF NOT EXISTS UnifiedEventsTable (" +
                    "StreamId INTEGER, ProcessId INTEGER, GroupId INTEGER, Kind INTEGER, " +
                    "CommandId INTEGER, Timestamp INTEGER, Data BLOB)",
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS index_UnifiedEventsTable_Kind " +
                    "ON UnifiedEventsTable(Kind)",
            )
        }
        connection.autoCommit = false
        insert = connection.prepareStatement(
            "INSERT INTO UnifiedEventsTable " +
                "(StreamId, ProcessId, GroupId, Kind, CommandId, Timestamp, Data) " +
                "VALUES (1, ?, 0, ?, 0, ?, ?)",
        )
    }

    fun insert(processId: Int, row: AsdbRow) {
        insert.setInt(1, processId)
        insert.setInt(2, row.kind)
        insert.setLong(3, row.timestampNs)
        insert.setBytes(4, row.data)
        insert.addBatch()
    }

    fun flush() {
        insert.executeBatch()
        insert.clearBatch()
        connection.commit()
    }

    override fun close() {
        try {
            insert.close()
            connection.close()
        } catch (_: Exception) {
        }
    }
}
