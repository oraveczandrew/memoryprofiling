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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import hu.oandras.ksvg.SVG
import hu.oandras.memoryprofiling.ArtHeapProfiler
import hu.oandras.memoryprofiling.AsdbConfig
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class SvgMapCaptureTest {

    @Test
    fun recordsAllocationsWhileParsingSvgMap() {
        assumeTrue(ArtHeapProfiler.isAsdbSupported())

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val result = ArtHeapProfiler.recordAsdb(
            label = "svg-map",
            config = AsdbConfig(sampleEvery = 1, stackDepth = 16),
        ) {
            val svg = SVG.getFromAsset(context.assets, "hungary_location_map.svg")
            assertTrue(svg.documentWidth > 0f)
            assertTrue(svg.documentHeight > 0f)
        }

        val file = File(result.path)
        assertTrue(file.length() > 0L)

        val top = AsdbTopAllocations.read(file)
        assertTrue("Expected allocation event rows", top.isNotEmpty())

        println("Top ${top.size} allocated classes:")
        top.forEachIndexed { index, allocation ->
            println("${index + 1}. ${allocation.className}: ${allocation.count}")
        }
    }
}
