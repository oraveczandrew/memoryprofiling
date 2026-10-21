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

/** Kind of artifact produced by a profiling run. */
enum class ProfileKind {
    /** Perfetto trace containing `heapprofd` samples. */
    HEAPPROFD,

    /** Android HPROF heap dump (live objects only). */
    HPROF,

    /** Android Studio memory-profiler allocation capture (`.asdb`). */
    ASDB,
}

/**
 * Result of a profiling run.
 *
 * @property label Caller-provided label.
 * @property path Absolute path of the produced artifact.
 * @property kind Artifact kind.
 * @property droppedAllocations Allocations discarded because the native buffer was full. Only
 * `.asdb` captures can report drops. A non-zero value means the capture is incomplete.
 */
data class ProfileResult(
    @JvmField val label: String,
    @JvmField val path: String,
    @JvmField val kind: ProfileKind,
    @JvmField val droppedAllocations: Long,
)
