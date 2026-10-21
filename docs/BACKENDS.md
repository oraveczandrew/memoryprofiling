# Choosing a backend

All entry points live on `ArtHeapProfiler`. The table below is a quick decision
guide; details follow.

|                      | `.asdb`      | heapprofd                  | counters    | HPROF                  |
|----------------------|--------------|----------------------------|-------------|------------------------|
| Exact per allocation | yes          | no (sampled)               | totals only | no                     |
| Call stacks          | yes          | yes                        | no          | yes (retained objects) |
| Short-lived objects  | yes          | often                      | counted     | no                     |
| Native heap          | no           | yes (`Heap.NATIVE`)        | no          | no                     |
| Minimum API          | 28           | 29 native / 31 ART         | 26          | 26                     |
| Process requirement  | `debuggable` | `profileable`/`debuggable` | none        | none                   |
| Output               | `.asdb`      | Perfetto trace             | in memory   | `.hprof`               |
| Overhead             | high         | medium                     | low         | medium                 |

## `.asdb` (`recordAsdb`)

Use when you need the exact allocation call sites, including objects that do not
survive to the end of the block. This is the only exact per-allocation backend.

- Requires API 28+ and a `debuggable` test APK. `profileable` is not enough for
  JVMTI agents.
- Records one out of every `sampleEvery` allocations; `1` means all of them.
- `stackDepth` controls how many frames are stored (`0` disables stacks).
- The file is written to `context.filesDir` and reported as an additional test
  output when called from an instrumentation test. The `Context` overload also
  works in a debuggable application.
- Not reentrant; concurrent or nested captures fail immediately.
- The native buffer holds up to 250,000 events; anything beyond that is counted
  in `ProfileResult.droppedAllocations` and logged as a warning.
- Kind 408 and kind 409 rows use the same wall-clock timestamp domain.

## heapprofd (`record`)

Use when a sampled profile is acceptable, when you need the native heap, or when
you want a Perfetto trace that can be opened in the Perfetto UI.

- Native heap needs API 29+; ART heap needs API 31+.
- Requires a `profileable` or `debuggable` test APK.
- Sampling interval and buffer size are configured with `HeapprofdConfig`.
- Not reentrant; do not combine with `BenchmarkRule` or another Perfetto
  recording in the same test method.

## Counters (`measureCounters`)

Use for cheap, exact totals or for A/B comparisons of the same workload. The
counters are process-wide and include test-harness allocations, so do not use
them as per-object measurements.

## HPROF (`dumpHprof`)

Use to inspect objects that are still alive at the end of a block (for example,
to find a leak). It is a snapshot, not an allocation trace; short-lived objects
are absent.

## Combining backends

Use separate test methods for different backends. In particular, `.asdb` and
heapprofd both attach profiling machinery to the process and should not run at
the same time.
