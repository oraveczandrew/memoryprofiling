# Memory Profiling Test Utils

Instrumentation-test helpers for profiling memory allocations in Android apps.

The library is meant to be added to a module's `androidTest` configuration and used from
instrumentation tests. It is not a benchmarking framework and does not measure timing.

## Backends

| Backend | What it provides | Requirements |
| --- | --- | --- |
| Perfetto `heapprofd` | Sampled allocation call stacks for the ART and native heaps | Native heap: Android 10 (API 29)+. ART heap: Android 12 (API 31)+. `profileable` or `debuggable` test APK |
| ART allocation counters | Exact process-wide allocation count and byte totals | Any supported API level |
| HPROF dump | Live objects at dump time, for retained-size analysis | Any supported API level, debuggable or profileable process |

`heapprofd` is sampled: small or short-lived allocations can be missing. Lower
`HeapprofdConfig.samplingIntervalBytes` for better coverage at the cost of overhead. Allocation
counters are exact but process-wide, so test-harness allocations are included. HPROF does not
contain short-lived allocations because it only sees objects that are still reachable.

## Setup

Add the library repository as a Git submodule and include it as a Gradle project. The module is
built as an Android library with the `hu.oandras.memoryprofiling` namespace.

```kotlin
// settings.gradle.kts
include(":androidMemoryProfilingTestUtils")
```

```kotlin
// consumer module build.gradle.kts
dependencies {
    androidTestImplementation(project(":androidMemoryProfilingTestUtils"))
}
```

The test APK must be `profileable` or `debuggable` for `heapprofd` and HPROF. Instrumentation test
APKs are debuggable by default; release-like test builds need this in the test manifest:

```xml
<application>
    <profileable android:shell="true" />
</application>
```

## Usage

### Allocation call sites with heapprofd

```kotlin
@Test
fun parserDoesNotAllocatePerItem() {
    assumeTrue(ArtHeapProfiler.isHeapprofdSupported())

    ArtHeapProfiler.record("parse") {
        parser.parse(input)
    }
}
```

By default the ART heap is sampled. Native allocations can be added explicitly:

```kotlin
ArtHeapProfiler.record(
    label = "native",
    config = HeapprofdConfig(
        heaps = setOf(Heap.ART, Heap.NATIVE),
        samplingIntervalBytes = 1024,
    ),
) {
    workload()
}
```

`record` is not reentrant and must not be combined with `BenchmarkRule`,
`MacrobenchmarkRule`, or other Perfetto recording in the same test method.

### Exact allocation totals

```kotlin
val result = ArtHeapProfiler.measureCounters {
    parser.parse(input)
}

println("${result.allocatedCount} allocations, ${result.allocatedBytes} bytes")
```

### Retained objects with HPROF

```kotlin
val result = ArtHeapProfiler.dumpHprof("after-parse")
```

## Output

`ProfileResult.path` points to the produced artifact. Trace and heap-dump files are written to the
test app's external files directory and reported to the test runner as additional test output, so
Android Studio and CI tooling can pull them from the device.

`heapprofd` traces can be opened with [Perfetto UI](https://ui.perfetto.dev) (select the
`android.heapprofd` data source). HPROF dumps can be opened with Android Studio's Memory Profiler or
any HPROF-compatible heap analyzer.

## Limitations

- `heapprofd` requires a `profileable` or `debuggable` process. ART heap sampling requires Android
  12 (API 31) or newer; native heap sampling works from Android 10 (API 29).
- The native heap is only available through heapprofd; allocation counters and HPROF cover the ART
  heap.
- Perfetto recording is process-wide and not thread-isolated. Run it from a single test method and
  avoid concurrent profiling.
- Allocation counters are deprecated Android APIs and count the whole process, including the test
  harness.

## License

Apache License 2.0. See [LICENSE](LICENSE).
