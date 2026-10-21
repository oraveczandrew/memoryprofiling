# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project aims to
follow [Semantic Versioning](https://semver.org/).

## Unreleased

## 0.1.0

### Added

- Direct ART allocation capture into an Android Studio `.asdb` database using a
  JVMTI `VMObjectAlloc` agent.
- Perfetto `heapprofd` capture for ART and native heaps.
- Exact process-wide allocation counters.
- HPROF heap dumps.
- Sample application demonstrating `.asdb` capture while parsing an SVG map.
