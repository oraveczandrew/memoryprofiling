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

import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupConverterTest {

    @Test
    fun convertsCaptureIntoAsdb() {
        val directory = File.createTempFile("startup-converter", "").also {
            it.delete()
            it.mkdirs()
        }
        try {
            val input = File(directory, "capture.raw")
            input.writeBytes(rawCapture())
            val done = File(directory, "capture.raw.done")
            done.writeText(
                "version=1\npid=1234\nstartNs=1700000000000000000\n" +
                    "endNs=1700000001000000000\ndropped=7\n",
            )
            val output = File(directory, "capture.asdb")

            val summary = convertCapture(
                input = input,
                output = output,
                doneMarker = done,
                force = false,
                pidOverride = null,
            )

            assertEquals(1L, summary.batches)
            assertEquals(1L, summary.allocations)
            assertEquals(7L, summary.dropped)
            assertEquals(1234, summary.processId)
            assertTrue(summary.ended)
            assertTrue(summary.hasDoneMarker)

            DriverManager.getConnection("jdbc:sqlite:${output.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT Kind, COUNT(*) FROM UnifiedEventsTable GROUP BY Kind ORDER BY Kind",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(408, result.getInt(1))
                        assertEquals(1, result.getInt(2))
                        assertTrue(result.next())
                        assertEquals(409, result.getInt(1))
                        assertEquals(1, result.getInt(2))
                    }
                    statement.executeQuery(
                        "SELECT ProcessId, Timestamp FROM UnifiedEventsTable " +
                            "ORDER BY Kind LIMIT 1",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1234, result.getInt(1))
                        assertEquals(1_700_000_000_000_000_000L, result.getLong(2))
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun refusesToOverwriteWithoutForce() {
        val directory = File.createTempFile("startup-converter", "").also {
            it.delete()
            it.mkdirs()
        }
        try {
            val input = File(directory, "capture.raw")
            input.writeBytes(rawCapture())
            val output = File(directory, "capture.asdb")
            output.writeText("existing")

            val error = assertThrows(Exception::class.java) {
                convertCapture(
                    input = input,
                    output = output,
                    doneMarker = null,
                    force = false,
                    pidOverride = null,
                )
            }

            assertTrue(error.message!!.contains("Output already exists"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun rawCapture(): ByteArray {
        val writer = LittleEndianWriter()
        writer.raw(StartupRawReader.MAGIC)
        writer.int(1)
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
            int(0)
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
        writer.int(0)
        return writer.toByteArray()
    }
}
