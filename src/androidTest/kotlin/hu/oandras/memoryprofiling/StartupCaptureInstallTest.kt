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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class StartupCaptureInstallTest {

    @Test
    fun installsAndRemovesAgent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(StartupCapture.isSupported(context))

        val installation = StartupCapture.install(
            context,
            StartupCaptureConfig(sampleEvery = 2, stackDepth = 8, durationMs = 1_000),
        )
        try {
            assertTrue(installation.agentFile.isFile)
            assertTrue(installation.agentFile.length() > 0L)
            assertTrue(installation.configFile.isFile)
            assertTrue(installation.configFile.readText().contains("sampleEvery=2"))
            assertTrue(StartupCapture.requestStop(context))
            assertTrue(installation.stopFile.isFile)
        } finally {
            StartupCapture.uninstall(context)
        }

        assertFalse(installation.agentFile.exists())
        assertFalse(installation.configFile.exists())
        assertFalse(installation.stopFile.exists())
    }
}
