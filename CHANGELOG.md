# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to
follow [Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- Application-startup allocation capture. A separate JVMTI agent is attached by
  the framework before the app is bound, records to a raw file, and is converted
  into an Android Studio `.asdb` on the host.
- `StartupCapture` deployment helpers and `StartupCaptureConfig` for installing,
  stopping, and removing the startup agent from a debuggable app.
- `:startup-converter` JVM CLI and `tools/startup-capture.sh` adb helper.
- Host tests for the raw startup format and conversion, plus an instrumentation
  test for installing and removing the startup agent.
- The shared `.asdb` row encoder used by both the on-device writer and the host
  converter.

### Fixed

- JVMTI allocation callbacks no longer hold the pending-buffer lock while
  calling JVMTI. Attaching a second allocation agent in the same process could
  otherwise block the startup capture writer before it finalized its files.
- `heapprofd` capture no longer fails on Android 11 and 12 with "did not detect
  tracing on". Those releases poll `tracing_on`, which stays off for a
  heapprofd-only session, so the capture config now also enables a minimal
  ftrace data source on API 30-32.

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
