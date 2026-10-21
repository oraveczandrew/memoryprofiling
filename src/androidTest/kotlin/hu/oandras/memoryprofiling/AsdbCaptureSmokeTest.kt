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

package hu.oandras.memoryprofiling

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@SdkSuppress(minSdkVersion = 28)
@RunWith(AndroidJUnit4::class)
class AsdbCaptureSmokeTest {

    @Test
    fun recordsAllocationsIntoAsdb() {
        assumeTrue(ArtHeapProfiler.isAsdbSupported())

        val capture = ArtHeapProfiler.recordAsdb(
            label = "asdb-smoke",
            config = AsdbConfig(sampleEvery = 1, stackDepth = 16),
        ) {
            val values = ArrayList<String>(ALLOCATIONS)
            repeat(ALLOCATIONS) {
                values.add("allocation-$it")
            }
            check(values.size == ALLOCATIONS)
        }
        assertTrue("Capture failed: $capture", capture is AsdbCaptureResult.Success)

        val file = File((capture as AsdbCaptureResult.Success).profile.path)
        assertTrue(file.length() > 0L)

        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val events = db.rawQuery(
                "SELECT COUNT(*) FROM UnifiedEventsTable WHERE Kind = 409",
                null,
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
            assertTrue("Expected allocation event rows", events > 0L)

            val contexts = db.rawQuery(
                "SELECT COUNT(*) FROM UnifiedEventsTable WHERE Kind = 408",
                null,
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
            assertTrue("Expected context rows", contexts > 0L)
        }
    }

    private companion object {
        const val ALLOCATIONS = 20_000
    }
}
