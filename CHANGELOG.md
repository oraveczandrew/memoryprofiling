# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to
follow [Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- Direct ART allocation capture into an Android Studio `.asdb` database using a
  JVMTI `VMObjectAlloc` agent.
- Perfetto `heapprofd` capture for ART and native heaps.
- Exact process-wide allocation counters.
- HPROF heap dumps.
- Sample application demonstrating `.asdb` capture while parsing an SVG map.
- `Context` overloads of `isAsdbSupported` and `recordAsdb` for debuggable
  applications outside instrumentation tests.
- Host-side unit tests for protobuf encoding and heapprofd configuration.
- Instrumentation tests for unsupported API levels, invalid configuration,
  workload exception propagation, and repeated captures.

### Changed

- `recordAsdb` can now be called on every API level and returns a sealed
  `AsdbCaptureResult` (`Success`/`NotSupported`/`Failure`) instead of throwing on
  capture errors.

### Fixed

- An empty capture is now reported as a failure instead of a successful result.
- Repeated captures in the same process now emit complete class, method, stack,
  and thread metadata for every capture.
- Changed capture configuration is applied on subsequent captures.
- The sample application now captures allocations on launch and handles window
  insets and system bar appearance.
