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

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import hu.oandras.ksvg.SVG
import hu.oandras.memoryprofiling.ArtHeapProfiler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this)
        setContentView(status)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !ArtHeapProfiler.isAsdbSupported()) {
            status.text = "Allocation capture is not supported on this device."
            return
        }

        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val result = ArtHeapProfiler.recordAsdb("sample") {
                        val svg = SVG.getFromAsset(assets, "hungary_location_map.svg")
                        require(svg.documentWidth > 0f && svg.documentHeight > 0f)
                    }

                    val top = AsdbTopAllocations.read(File(result.path))
                    buildString {
                        appendLine("Captured allocations: ${result.path}")
                        appendLine()
                        appendLine("Top ${top.size} allocated classes:")
                        top.forEachIndexed { index, allocation ->
                            appendLine("${index + 1}. ${allocation.className}: ${allocation.count}")
                        }
                    }
                } catch (e: Exception) {
                    "Capture failed: ${e.message}"
                }
            }

            status.text = text
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
