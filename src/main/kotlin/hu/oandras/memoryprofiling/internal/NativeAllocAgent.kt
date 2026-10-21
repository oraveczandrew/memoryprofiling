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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

internal object NativeAllocAgent {

    private const val LIBRARY_NAME = "liballoc_agent.so"

    private var loadError: Throwable? = null
    private var loaded = false
    private var agentFile: File? = null

    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        ensureLoaded(context)
        return loadError == null
    }

    fun attach(context: Context, sampleEvery: Int, stackDepth: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw AsdbCaptureException("JVMTI agents require Android 9 (API 28) or newer.")
        }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            throw AsdbCaptureException(
                "JVMTI allocation tracking requires a debuggable app or test APK.",
            )
        }
        ensureLoaded(context)
        loadError?.let { throw AsdbCaptureException("Unable to load the allocation agent.", it) }

        if (!nativeIsAttached()) {
            val library = agentFile
                ?: throw AsdbCaptureException("The allocation agent has no file path.")
            val options = "sampleEvery=$sampleEvery,depth=$stackDepth"
            val error = try {
                Debug.attachJvmtiAgent(
                    library.absolutePath,
                    options,
                    NativeAllocAgent::class.java.classLoader,
                )
                null
            } catch (e: IOException) {
                e
            } catch (e: UnsatisfiedLinkError) {
                e
            } catch (e: IllegalArgumentException) {
                e
            }
            if (error != null) {
                throw AsdbCaptureException("Unable to attach the JVMTI allocation agent.", error)
            }
            if (!nativeIsAttached()) {
                throw AsdbCaptureException("The JVMTI allocation agent did not attach.")
            }
        }
        nativeConfigure(sampleEvery, stackDepth)
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) {
            return
        }
        loadError = try {
            val file = resolveAgentFile(context)
            System.load(file.absolutePath)
            agentFile = file
            loaded = true
            null
        } catch (e: Throwable) {
            e
        }
    }

    private fun resolveAgentFile(context: Context): File {
        val directory = context.codeCacheDir
        directory.mkdirs()
        val target = File(directory, "alloc_agent.so")

        val extracted = File(context.applicationInfo.nativeLibraryDir, LIBRARY_NAME)
        if (extracted.isFile) {
            extracted.copyTo(target, overwrite = true)
        } else {
            val abis = if (Process.is64Bit()) {
                Build.SUPPORTED_ABIS.filter { it.contains("64") }
            } else {
                Build.SUPPORTED_ABIS.filterNot { it.contains("64") }
            }
            ZipFile(context.applicationInfo.sourceDir).use { zip ->
                val entry = abis.asSequence()
                    .map { "lib/$it/$LIBRARY_NAME" }
                    .firstNotNullOfOrNull { path -> zip.getEntry(path)?.let { path to it } }
                    ?: throw IllegalStateException("$LIBRARY_NAME is missing from the APK.")
                zip.getInputStream(entry.second).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
        }
        target.setReadable(true, true)
        target.setExecutable(true, true)
        return target
    }

    fun start() {
        nativeStart()
    }

    fun stop() {
        nativeStop()
    }

    fun drain(): ByteArray? = nativeDrain()

    fun droppedEvents(): Long = nativeDropped()

    @JvmStatic
    private external fun nativeConfigure(sampleEvery: Int, stackDepth: Int)

    @JvmStatic
    private external fun nativeStart()

    @JvmStatic
    private external fun nativeStop()

    @JvmStatic
    private external fun nativeIsAttached(): Boolean

    @JvmStatic
    private external fun nativeDrain(): ByteArray?

    @JvmStatic
    private external fun nativeDropped(): Long
}
