# Android Memory Profiling Test Utils

Record every ART allocation with its call stack from an instrumentation test,
directly into an Android Studio `.asdb` database.

[![Build](https://github.com/oraveczandrew/memoryprofiling/actions/workflows/build.yml/badge.svg)](https://github.com/oraveczandrew/memoryprofiling/actions/workflows/build.yml)
[![GitHub release](https://img.shields.io/github/v/release/oraveczandrew/memoryprofiling)](https://github.com/oraveczandrew/memoryprofiling/releases)
[![JitPack](https://jitpack.io/v/oraveczandrew/memoryprofiling.svg)](https://jitpack.io/#oraveczandrew/memoryprofiling)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

## Why this exists

Perfetto `heapprofd` samples allocations, so small or short-lived objects can be
missed. This library adds a JVMTI agent that records allocations exactly with
their call stacks and writes them into an Android Studio memory-profiler `.asdb`
database on the device. There is no host-side conversion step: pull the file and
open it.

The library also wraps the other allocation backends used by instrumentation
tests, so the same test suite can compare exact capture, sampled profiles,
allocation counters, and live-heap dumps.

## Features

| Backend            | What you get                             | Exact vs sampled | Min API | Process requirement      | Artifact          |
|--------------------|------------------------------------------|------------------|---------|--------------------------|-------------------|
| `.asdb` (JVMTI)    | Per-allocation records with stack traces | Exact            | 28      | `debuggable`             | `.asdb`           |
| heapprofd (ART)    | Sampled ART heap profile                 | Sampled          | 31      | `profileable`/debuggable | `.perfetto-trace` |
| heapprofd (native) | Sampled native heap profile              | Sampled          | 29      | `profileable`/debuggable | `.perfetto-trace` |
| Counters           | Exact process-wide allocation totals     | Exact totals     | 26      | none                     | in memory         |
| HPROF              | Live-object heap dump                    | Snapshot         | 26      | none                     | `.hprof`          |

## Requirements

- JDK 21; Gradle 9.8.2 and AGP 9.4.1 (the versions used by this repository).
- Android SDK platform 37 and build-tools 37.0.0.
- NDK 29.0.14206865 and CMake 3.22.1 (needed to build the JVMTI agent).
- API levels per backend, as listed in the table above.
- A `debuggable` test APK for `.asdb` and HPROF; `profileable` or `debuggable`
  for heapprofd.
- `adb` or the test runner's additional test output to retrieve artifacts.

## Installation

JitPack is the recommended way to consume a release, because it resolves the
library's transitive dependencies automatically. Add the JitPack repository and
the dependency:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    androidTestImplementation("com.github.oraveczandrew:memoryprofiling:<tag>")
}
```

The `Context` overloads can also be used from a debuggable application, but
artifacts are then not reported to a test runner.

### Raw AAR

Release AARs are attached to GitHub Releases. A raw AAR carries no POM, so add
its runtime dependencies manually:

```kotlin
androidTestImplementation(files("libs/android-memory-profiling-test-utils-<version>.aar"))
androidTestImplementation("androidx.benchmark:benchmark-common:1.6.0-alpha01")
androidTestImplementation("androidx.benchmark:benchmark-traceprocessor:1.6.0-alpha01")
androidTestImplementation("androidx.test:monitor:1.9.0-alpha02")
```

Consumers of the raw AAR need `compileSdk = 37`, matching the AAR metadata.
The AAR contains `liballoc_agent.so` for `arm64-v8a`, `armeabi-v7a`, `x86` and
`x86_64`.

### Project dependency

You can also consume the repository as a Git submodule and add it as a project
dependency:

```kotlin
androidTestImplementation(project(":android-memory-profiling-test-utils"))
```

## Quick start: `.asdb` capture

```kotlin
@Test
fun captureAllocations() {
    assumeTrue(ArtHeapProfiler.isAsdbSupported())

    val capture = ArtHeapProfiler.recordAsdb(
        label = "parser",
        config = AsdbConfig(sampleEvery = 1, stackDepth = 32),
    ) {
        parser.parse(input)
    }

    when (capture) {
        is AsdbCaptureResult.Success -> println(capture.profile.path)
        is AsdbCaptureResult.NotSupported -> println(capture.message)
        is AsdbCaptureResult.Failure -> println(capture.message)
    }
}
```

- `sampleEvery` records one out of every N allocations. `1` records every
  allocation and has the highest overhead.
- `stackDepth` limits the number of stored frames per allocation; `0` disables
  stack capture.
- The `.asdb` file is written to the test application's `context.filesDir` and
  registered as an additional test output.
- `capture.profile.droppedAllocations` is non-zero when the native buffer filled
  up; the capture is still returned, but those allocation events are missing.
- The target process must be `debuggable`; `profileable` alone is not enough for
  JVMTI. The test must not run inside `BenchmarkRule` timing.

To capture from a debuggable application instead of a test, pass a `Context`:

```kotlin
val capture = ArtHeapProfiler.recordAsdb(context, "parser") {
    parser.parse(input)
}
```

Unsupported processes return `AsdbCaptureResult.NotSupported`, other capture
failures return `AsdbCaptureResult.Failure`; exceptions thrown by the block
propagate normally. The file is written to `context.filesDir` and
returned in `Success.profile.path`.

## Quick start: heapprofd

```kotlin
@Test
fun sampleArtHeap() {
    assumeTrue(ArtHeapProfiler.isHeapprofdSupported(Heap.ART))

    val result = ArtHeapProfiler.record("parser") {
        parser.parse(input) // do not use BenchmarkRule timing here
    }

    println(result.path) // Perfetto trace
}
```

Use `HeapprofdConfig` to change the sampling interval, shared memory size,
dump interval, blocking behavior, or target process. On Android 10 and 11 only
`Heap.NATIVE` is available; ART heap sampling needs API 31.

## Quick start: allocation counters

```kotlin
val counters = ArtHeapProfiler.measureCounters {
    parser.parse(input)
}
println("${counters.allocatedCount} allocations, ${counters.allocatedBytes} bytes")
```

Counters are process-wide, so the test harness is included. Use them for
relative comparisons, not as per-object measurements.

## Quick start: HPROF

```kotlin
val result = ArtHeapProfiler.dumpHprof("after-parse")
println(result.path)
```

HPROF contains only objects alive at dump time; short-lived allocations are not
included.

## Opening the results

- `.asdb`: Android Studio **View > Tool Windows > Profiler**, then open the
  memory profiler and load the `.asdb` file. It is an SQLite database; see
  [docs/ASDB_FORMAT.md](docs/ASDB_FORMAT.md) for its schema.
- heapprofd: open the Perfetto trace in the [Perfetto UI](https://ui.perfetto.dev)
  (the `android.heapprofd` data source).
- HPROF: open it from Android Studio's memory profiler or analyze it with the
  standard HPROF tooling.

Artifacts are also reported through the instrumentation runner's additional test
output, so the Android Gradle plugin can copy them from the device when the test
finishes.

## How `.asdb` capture works

- A JVMTI agent receives `VMObjectAlloc` events and buffers per-thread records
  (up to 250,000 pending events; excess events are counted as dropped).
- The agent samples every Nth allocation when `sampleEvery` is greater than one 
  and unwinds the requested number of frames.
- A background thread drains the native buffer into an SQLite writer.
- The writer emits protobuf payloads into `UnifiedEventsTable`:
  kind 408 for classes, methods, stacks, and threads; kind 409 for allocation
  events.
- The result is a database that Android Studio's memory profiler can open
  directly.

## Limitations

- The `.asdb` schema is reverse-engineered from observed Android Studio output,
  not a published contract. It may change with Android Studio or Android
  releases.
- Full tracking is expensive: every recorded allocation unwinds a stack. Prefer
  `sampleEvery > 1` or heapprofd for large workloads.
- Capture is process-wide, not per-thread or per-object-graph.
- The API is not reentrant; do not nest captures or combine them with
  `BenchmarkRule`/Perfetto recording in the same test.
- `.asdb` captures only the ART heap. Native allocations are not included.
- `.asdb` needs API 28+ and a `debuggable` process.

## Development

```bash
./gradlew :assembleDebug :lintDebug
./gradlew :connectedDebugAndroidTest
```

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the module layout, the JVMTI
setup, and how to inspect a captured `.asdb` with `sqlite3`.

## License

The repository's own code is licensed under the Apache License, Version 2.0
(see [LICENSE](LICENSE)). `src/main/cpp/include/jvmti.h` is a vendored
OpenJDK/Oracle header under GPLv2 with the Classpath Exception and is not
covered by the Apache license; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
