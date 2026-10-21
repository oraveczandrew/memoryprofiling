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

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import hu.oandras.memoryprofiling.ProfileKind
import hu.oandras.memoryprofiling.ProfileResult
import java.io.File

internal object HprofDump {

    fun dump(label: String): ProfileResult {
        val file = OutputFiles.create(label, "hprof")

        Debug.dumpHprofData(file.absolutePath)
        check(file.exists() && file.length() > 0L) {
            "HPROF dump was not written to ${file.absolutePath}."
        }

        OutputFiles.report(file)
        return ProfileResult(label = label, path = file.absolutePath, kind = ProfileKind.HPROF)
    }
}

internal object OutputFiles {

    private const val RESULT_KEY_PREFIX = "additionalTestOutputFile_"
    private const val IN_PROGRESS_STATUS = 2

    fun create(label: String, extension: String): File =
        create(label, extension, external = true)

    fun createInternal(label: String, extension: String): File =
        create(label, extension, external = false)

    private fun create(label: String, extension: String, external: Boolean): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = if (external) {
            context.getExternalFilesDir(null) ?: context.cacheDir
        } else {
            context.filesDir
        }

        directory.mkdirs()
        check(directory.isDirectory) {
            "Unable to create the output directory ${directory.absolutePath}."
        }

        return File(directory, "${sanitize(label)}_${System.currentTimeMillis()}.$extension")
    }

    fun report(file: File) {
        val bundle = android.os.Bundle().apply {
            putString("$RESULT_KEY_PREFIX${file.name}", file.absolutePath)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(IN_PROGRESS_STATUS, bundle)
    }

    private fun sanitize(label: String): String =
        label.replace(Regex("[^0-9a-zA-Z._-]+"), "_")
}
