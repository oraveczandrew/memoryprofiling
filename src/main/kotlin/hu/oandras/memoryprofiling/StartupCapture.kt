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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Captures ART allocations from before [android.app.Application.onCreate].
 *
 * The agent must be attached by the framework before the app is bound, so it cannot start in the
 * same process that calls [install]. Install the agent, force-stop the app, then start it again.
 * The raw capture is converted into an Android Studio `.asdb` file on the host; see the README.
 *
 * The feature requires Android 10 (API 29) or newer and a `debuggable` application.
 */
object StartupCapture {

    private const val AGENT_FILE_NAME = "liballoc_agent_startup.so"
    private const val CAPTURE_DIRECTORY = "startup_capture"
    private const val AGENTS_DIRECTORY = "startup_agents"
    private const val CONFIG_FILE_NAME = "config"
    private const val STOP_FILE_NAME = "startup_capture.stop"

    /**
     * Whether startup capture can be installed for [context].
     *
     * This checks the API level and the `debuggable` flag; it does not verify that the caller is
     * allowed to write the application's code cache.
     */
    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Installs [config] and the startup agent for the next process start.
     *
     * Any previous capture for the same [StartupCaptureConfig.outputFileName] is removed. Call
     * this from the current process, then force-stop and relaunch the app.
     *
     * @throws IllegalArgumentException if [StartupCaptureConfig.outputFileName] is not a plain
     * file name.
     * @throws IllegalStateException if the current process cannot use startup capture.
     * @throws IOException if the agent or configuration cannot be written.
     */
    fun install(
        context: Context,
        config: StartupCaptureConfig = StartupCaptureConfig(),
    ): StartupCaptureInstallation {
        if (!isSupported(context)) {
            throw IllegalStateException(
                "Startup capture requires Android 10 (API 29) or newer and a debuggable app.",
            )
        }
        require(config.sampleEvery >= 1) { "sampleEvery must be at least 1." }
        require(config.stackDepth in 0..256) { "stackDepth must be between 0 and 256." }
        require(config.durationMs >= 0) { "durationMs must not be negative." }
        val outputFileName = validateFileName(config.outputFileName)

        val captureDirectory = File(context.codeCacheDir, CAPTURE_DIRECTORY)
        if (!captureDirectory.isDirectory && !captureDirectory.mkdirs()) {
            throw IOException("Unable to create $captureDirectory.")
        }
        val agentsDirectory = File(context.codeCacheDir, AGENTS_DIRECTORY)
        if (!agentsDirectory.isDirectory && !agentsDirectory.mkdirs()) {
            throw IOException("Unable to create $agentsDirectory.")
        }

        val captureFile = File(captureDirectory, outputFileName)
        val doneFile = File(captureFile.path + ".done")
        val stopFile = File(captureDirectory, STOP_FILE_NAME)
        captureFile.delete()
        doneFile.delete()
        stopFile.delete()

        val agentFile = File(agentsDirectory, AGENT_FILE_NAME)
        copyAgent(context, agentFile)

        val configFile = File(captureDirectory, CONFIG_FILE_NAME)
        val content = buildString {
            append("sampleEvery=").append(config.sampleEvery).append('\n')
            append("stackDepth=").append(config.stackDepth).append('\n')
            append("durationMs=").append(config.durationMs).append('\n')
            append("output=").append(outputFileName).append('\n')
            append("stopFile=").append(STOP_FILE_NAME).append('\n')
        }
        configFile.writeText(content)

        return StartupCaptureInstallation(
            agentFile = agentFile,
            configFile = configFile,
            captureFile = captureFile,
            stopFile = stopFile,
        )
    }

    /**
     * Removes the installed agent and its configuration.
     *
     * Captures are left in place. The change takes effect on the next process start; an already
     * running capture continues until its duration elapses or [requestStop] is called.
     */
    fun uninstall(context: Context) {
        val agentsDirectory = File(context.codeCacheDir, AGENTS_DIRECTORY)
        File(agentsDirectory, AGENT_FILE_NAME).delete()
        val captureDirectory = File(context.codeCacheDir, CAPTURE_DIRECTORY)
        File(captureDirectory, CONFIG_FILE_NAME).delete()
        File(captureDirectory, STOP_FILE_NAME).delete()
    }

    /**
     * Requests that the running startup capture stops and finalizes its raw file and `.done`
     * marker.
     *
     * @return `true` if the request file was created, `false` if a request is already pending.
     */
    fun requestStop(context: Context): Boolean {
        val captureDirectory = File(context.codeCacheDir, CAPTURE_DIRECTORY)
        if (!captureDirectory.isDirectory && !captureDirectory.mkdirs()) {
            return false
        }
        return try {
            File(captureDirectory, STOP_FILE_NAME).createNewFile()
        } catch (_: IOException) {
            false
        }
    }

    /** The raw capture written by the next process start. */
    fun captureFile(
        context: Context,
        fileName: String = "startup_capture.raw",
    ): File = File(File(context.codeCacheDir, CAPTURE_DIRECTORY), validateFileName(fileName))

    /** Whether the agent has finished and written the `.done` marker. */
    fun isCaptureComplete(
        context: Context,
        fileName: String = "startup_capture.raw",
    ): Boolean = File(captureFile(context, fileName).path + ".done").isFile

    private fun validateFileName(fileName: String): String {
        require(fileName.isNotBlank()) { "The output file name must not be blank." }
        require(fileName != "." && fileName != "..") { "Use a plain file name: $fileName." }
        require(fileName.none { it == '/' || it == '\\' || it == '\n' || it == '\r' }) {
            "Use a plain file name: $fileName."
        }
        return fileName
    }

    private fun copyAgent(context: Context, target: File) {
        val nativeLibrary = File(context.applicationInfo.nativeLibraryDir, AGENT_FILE_NAME)
        if (nativeLibrary.isFile) {
            nativeLibrary.copyTo(target, overwrite = true)
        } else {
            extractAgentFromApk(context, target)
        }
        target.setReadable(true, true)
        target.setExecutable(true, true)
    }

    private fun extractAgentFromApk(context: Context, target: File) {
        val abis = if (android.os.Process.is64Bit()) {
            Build.SUPPORTED_ABIS.filter { it.contains("64") }
        } else {
            Build.SUPPORTED_ABIS.filterNot { it.contains("64") }
        }
        ZipFile(context.applicationInfo.sourceDir).use { zip ->
            val entry = abis.asSequence()
                .map { "lib/$it/$AGENT_FILE_NAME" }
                .firstNotNullOfOrNull { path -> zip.getEntry(path)?.let { path to it } }
                ?: throw IOException("$AGENT_FILE_NAME is missing from the APK.")
            zip.getInputStream(entry.second).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
    }
}
