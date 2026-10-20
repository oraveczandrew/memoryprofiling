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
 * Exact allocation totals for a block, measured with the ART global allocation counters.
 *
 * The counters are process-wide and include allocations made by the test harness while the block
 * runs. Use them for relative comparisons of the same workload, not as an absolute per-object
 * measurement.
 *
 * @property allocatedCount Number of ART allocations.
 * @property allocatedBytes Number of allocated bytes.
 * @property durationNanos Wall-clock duration of the measured block.
 */
data class CounterResult(
    @JvmField val allocatedCount: Long,
    @JvmField val allocatedBytes: Long,
    @JvmField val durationNanos: Long,
)
