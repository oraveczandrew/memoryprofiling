# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to
follow [Semantic Versioning](https://semver.org/).

## 0.1.0

### Added

- Direct ART allocation capture into an Android Studio `.asdb` database using a
  JVMTI `VMObjectAlloc` agent, with configurable sampling and stack depth.
- `Context` overloads of `isAsdbSupported` and `recordAsdb` for debuggable
  applications outside instrumentation tests.
- Sealed `AsdbCaptureResult` (`Success`/`NotSupported`/`Failure`) on every API
  level, with workload exceptions propagating unchanged.
- Perfetto `heapprofd` capture for ART and native heaps.
- Exact process-wide allocation counters and HPROF heap dumps.
- Consistent wall-clock timestamps across `.asdb` context and allocation rows.
- Distinct metadata entries for overloaded methods and complete metadata for
  repeated captures in the same process.
- Fail-fast handling for concurrent `.asdb` captures, explicit failure for empty
  captures, and `droppedAllocations` reporting when the native buffer fills up.
- Sample application demonstrating `.asdb` capture while parsing an SVG map,
  including edge-to-edge window insets.
- Host-side unit tests for protobuf encoding, heapprofd configuration, and the
  native buffer reader.
- Instrumentation tests for unsupported API levels, invalid configuration,
  workload exception propagation, repeated captures, and concurrent captures.
