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

/**
 * Result of an `.asdb` capture.
 *
 * Capture failures are returned as [Failure] instead of thrown exceptions. Exceptions thrown by
 * the caller-supplied block are not captured and propagate unchanged.
 */
sealed interface AsdbCaptureResult {

    /** The capture finished and [profile] points to the produced `.asdb` file. */
    data class Success(val profile: ProfileResult) : AsdbCaptureResult

    /**
     * The current process cannot produce an `.asdb` capture.
     *
     * @property message Human-readable explanation.
     */
    data class NotSupported(@JvmField val message: String) : AsdbCaptureResult

    /**
     * The capture could not be produced.
     *
     * @property message Human-readable explanation.
     * @property cause Underlying exception, when one exists.
     */
    data class Failure(
        @JvmField val message: String,
        @JvmField val cause: Throwable? = null,
    ) : AsdbCaptureResult
}
