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

import java.io.File

/**
 * Files written by [StartupCapture.install].
 *
 * The startup agent reads [configFile] on process start and writes [captureFile]. The file is
 * complete when a sibling `.done` marker exists; pass both files to the host-side converter.
 *
 * @property agentFile JVMTI agent installed in `code_cache/startup_agents`.
 * @property configFile Startup agent configuration.
 * @property captureFile Raw capture output of the next process start.
 * @property stopFile Creating this file stops a capture early.
 */
data class StartupCaptureInstallation(
    @JvmField val agentFile: File,
    @JvmField val configFile: File,
    @JvmField val captureFile: File,
    @JvmField val stopFile: File,
) {

    /** The marker the agent writes after it finished the capture. */
    val doneFile: File = File(captureFile.path + ".done")
}
