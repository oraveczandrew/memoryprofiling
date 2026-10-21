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
 * Configuration of a direct `.asdb` allocation capture.
 *
 * @property sampleEvery Record one out of every N allocations. `1` records every allocation and
 * has the highest overhead.
 * @property stackDepth Maximum number of stack frames stored per allocation. `0` disables stacks.
 */
data class AsdbConfig(
    @JvmField val sampleEvery: Int = 1,
    @JvmField val stackDepth: Int = 32,
)
