# Development

## Repository layout

- `src/main/kotlin/hu/oandras/memoryprofiling/` – public API
  (`ArtHeapProfiler`, configs, results).
- `src/main/kotlin/hu/oandras/memoryprofiling/internal/` – backends and the
  `.asdb` writer.
- `src/shared/kotlin/` – Android-independent `.asdb` row encoding used by both
  the device writer and the host startup converter.
- `src/main/kotlin/hu/oandras/memoryprofiling/StartupCapture*.kt` – deployment
  helpers for the application-startup agent.
- `src/main/cpp/alloc_agent.cpp` – JVMTI `VMObjectAlloc` agent. The same source
  builds the block-mode agent and the startup agent.
- `src/main/cpp/CMakeLists.txt` – native agent builds.
- `src/main/cpp/include/jvmti.h` – vendored OpenJDK header; see
  `THIRD_PARTY_NOTICES.md`.
- `startup-converter/` – JVM CLI that converts startup raw captures into
  `.asdb` files.
- `tools/startup-capture.sh` – adb helper for installing, stopping, pulling, and
  removing the startup agent.
- `src/androidTest/` – instrumentation smoke tests for the library.
- `sample/` – small application and instrumentation test demonstrating `.asdb`
  capture while parsing an SVG map with
  [ksvg](https://central.sonatype.com/artifact/hu.oandras.ksvg/ksvg).

## Toolchain

- JDK 21.
- Android SDK platform 37, build-tools 37.0.0.
- NDK 29.0.14206865 and CMake 3.22.1.
- Gradle 9.8.2 via the wrapper; AGP 9.4.1.

AGP 9 uses built-in Kotlin by default. This repository temporarily sets
`android.builtInKotlin=false` and `android.newDsl=false` so it can apply the
Kotlin Gradle plugin 2.4.21 (needed by the sample's ksvg dependency). Remove
those flags once the build no longer depends on them.

The library emits Kotlin metadata version 2.2.0 (with `languageVersion` and
`apiVersion` 2.2) so that consumers on older Kotlin versions can read its
classes. Do not raise it without a compatibility decision.

## Build

```bash
./gradlew :assembleDebug :lintDebug
./gradlew :sample:assembleDebug :sample:lintDebug
./gradlew :startup-converter:build
./gradlew :startup-converter:installDist
```

## Instrumentation tests

```bash
./gradlew :connectedDebugAndroidTest
./gradlew :sample:connectedDebugAndroidTest
```

The library tests are smoke tests and call `assumeTrue`/skip when the device or
build type cannot support a backend. `.asdb` tests need a `debuggable` test APK
and API 28+. heapprofd ART tests need API 31+; native tests need API 29+.

### Testing the JVMTI agent

1. Build and install a `debuggable` debug variant of the test APK.
2. Run a test that calls `ArtHeapProfiler.recordAsdb`.
3. Confirm `ArtHeapProfiler.isAsdbSupported()` is `true` on the device.
4. Retrieve the `.asdb` path printed by the test or copied through the test
   runner's additional test output.

The agents are packaged as `liballoc_agent.so` (block capture) and
`liballoc_agent_startup.so` (startup capture) for `arm64-v8a`, `armeabi-v7a`,
`x86` and `x86_64`. At runtime the library first uses the library from
`nativeLibraryDir` and otherwise extracts it from the APK into
`context.codeCacheDir`. `StartupCapture` copies the startup agent into
`code_cache/startup_agents/`.

### Testing the startup agent

See [STARTUP_CAPTURE.md](STARTUP_CAPTURE.md). On a device with a debuggable app
that contains the agent:

```bash
tools/startup-capture.sh install <package> --duration-ms 5000
adb shell am force-stop <package>
adb shell monkey -p <package> 1
tools/startup-capture.sh pull <package>
./gradlew :startup-converter:installDist
startup-converter/build/install/startup-converter/bin/startup-converter \
  startup_capture.raw startup_capture.asdb
```

## Inspecting a captured `.asdb`

```bash
adb pull /data/data/<test-package>/files/<label>_<timestamp>.asdb
sqlite3 <file> 'SELECT Kind, COUNT(*) FROM UnifiedEventsTable GROUP BY Kind;'
```

Kind 408 is the context row; kind 409 rows contain allocation events. See
`docs/ASDB_FORMAT.md` for the protobuf layout.

## Publishing a GitHub release

```bash
./gradlew :assembleRelease
```

The AAR is written to `build/outputs/aar/`. Attach it to a GitHub Release.
Releases are the only distribution channel for now; no Maven repository is
configured. Consumers add the AAR from `libs/` as described in the README.

## CI

`.github/workflows/build.yml` runs `:assembleDebug`, `:lintDebug`, the sample
build and lint, and `:startup-converter:build` on pushes and pull requests.
Emulator/instrumentation jobs are intentionally not part of the default PR path:
they are slow and costly. `.github/workflows/instrumented.yml` runs them after
pushes to the main branch and on manual dispatch, and keeps API-level coverage
explicit (28 for `.asdb`, 29 for startup capture and native heapprofd, 31+ for
ART heapprofd).
