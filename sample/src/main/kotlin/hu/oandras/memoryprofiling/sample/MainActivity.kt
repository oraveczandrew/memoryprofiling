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

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import hu.oandras.ksvg.SVG
import hu.oandras.memoryprofiling.ArtHeapProfiler
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val darkTheme = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val background = if (darkTheme) Color.BLACK else Color.WHITE
        val foreground = if (darkTheme) Color.WHITE else Color.BLACK
        window.setBackgroundDrawable(background.toDrawable())

        val status = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setTextColor(foreground)
            setBackgroundColor(background)
        }
        val basePadding = (16 * resources.displayMetrics.density).toInt()
        status.setPadding(basePadding, basePadding, basePadding, basePadding)
        ViewCompat.setOnApplyWindowInsetsListener(status) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom,
            )
            insets
        }
        setContentView(status)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            !ArtHeapProfiler.isAsdbSupported(this)
        ) {
            status.text = "Allocation capture requires Android 9 or newer and a debuggable app."
            return
        }

        status.text = "Capturing allocations..."
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val result = ArtHeapProfiler.recordAsdb(this@MainActivity, "sample") {
                        val svg = SVG.getFromAsset(assets, "hungary_location_map.svg")
                        require(svg.documentWidth > 0f && svg.documentHeight > 0f)
                    }

                    val top = AsdbTopAllocations.read(File(result.path))
                    buildString {
                        appendLine("Captured allocations:")
                        appendLine(result.path)
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
}
