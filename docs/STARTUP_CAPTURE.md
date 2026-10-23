# Application-startup allocation capture

The startup capture feature records ART allocations from before
`Application.attachBaseContext`, content providers, and `Application.onCreate`.
It uses a separate JVMTI agent that the Android framework attaches before the
application is bound, then converts the raw capture into an Android Studio
`.asdb` database on the host.

## Requirements

- Android 10 (API 29) or newer.
- A `debuggable` application.
- `adb` and a Unix shell for the optional helper script.
- JDK 21 for the host-side converter.

The application does not need root access. The framework only attaches startup
agents for debuggable processes; `profileable` is not enough.

## How it works

For debuggable apps, `ActivityManagerService` calls
`IApplicationThread.attachStartupAgents(dataDir)` before `bindApplication`.
`ActivityThread` then attaches every file in
`<dataDir>/code_cache/startup_agents/`. This happens before
`makeApplicationInner`, `attachBaseContext`, content providers, and
`Application.onCreate`.

The startup agent records `VMObjectAlloc` events into a compact raw file in
`<dataDir>/code_cache/startup_capture/`. It does not write SQLite on the device.
When the configured duration elapses or a stop file appears, the agent writes a
`.done` marker with the start time, end time, and dropped-event count.

The raw file is converted on the host with the same Kotlin protobuf and `.asdb`
row encoder that the on-device writer uses. The resulting database is identical
to a normal `.asdb` capture, except that allocations begin before app code runs.

## Install from application code

Call `StartupCapture.install` from the current process, then force-stop and
relaunch the app so the framework can attach the agent before binding.

```kotlin
val installation = StartupCapture.install(
    context,
    StartupCaptureConfig(
        sampleEvery = 1,
        stackDepth = 32,
        durationMs = 15_000,
    ),
)
println(installation.captureFile)
```

Stop a long-running capture early with `StartupCapture.requestStop(context)`.
Remove the agent and configuration with `StartupCapture.uninstall(context)`.
A capture with `durationMs = 0` records until a stop is requested.

`StartupCapture.isCaptureComplete(context)` becomes true when the `.done`
marker exists.

## Install through adb

The `tools/startup-capture.sh` helper reads the agent from the installed APK, so
the application must already contain this library.

```bash
tools/startup-capture.sh install <package> --duration-ms 10000
adb shell am force-stop <package>
adb shell monkey -p <package> 1

# Wait for the capture, or stop it early:
# tools/startup-capture.sh stop <package>

tools/startup-capture.sh pull <package>
```

`pull` writes `startup_capture.raw` and, when it exists, its `.done` marker to
the current directory. To remove the agent and configuration:

```bash
tools/startup-capture.sh uninstall <package>
```

The helper uses `run-as`, so it only works for debuggable applications. Use
`--apk <path>` to read the agent from a specific APK instead of the installed
one. When no device is specified with `-s`, adb uses its default device.

## Capture procedure

The recording duration starts when the framework attaches the agent, not when
you launch the app. Install first, then start exactly one fresh process:

```bash
# 1. Install for the next process start. Use --apk when the app does not bundle
#    this library (e.g. a third-party app that only needs the agent).
tools/startup-capture.sh install <package> --apk <agent.apk> \
  --duration-ms 12000 --sample-every 1 --stack-depth 32

# 2. Kill the current process and wait until it is really gone.
adb shell am force-stop <package>
for i in 1 2 3 4 5 6 7 8 9 10; do
  [ -z "$(adb shell pidof <package> | tr -d '\r')" ] && break
  sleep 0.2
done

# 3. Clear the log, then launch once.
adb logcat -c
adb shell am start -W -n <package>/<launcher-activity>

# 4. Confirm that exactly one process attached.
adb logcat -d -s AllocAgent
```

Step 2 matters: `force-stop` is asynchronous, and a home app or a widget
provider can be restarted by the system in the meantime. If a process is
already running when you launch, Android reuses it, the agent is not attached,
and the capture stays empty. Step 4 matters too: two processes of the same app
share one output path and overwrite each other, so retry with a clean stop when
you see more than one `Startup agent attached` line.

Wait for `durationMs` plus about one second, then pull and check the marker
before converting:

```bash
tools/startup-capture.sh pull <package>
cat startup_capture.raw.done        # dropped=0, pid matches the launched process

startup-converter/build/install/startup-converter/bin/startup-converter \
  startup_capture.raw startup_capture.asdb

# Remove the agent, configuration, and the device-side capture.
tools/startup-capture.sh uninstall <package>
adb shell run-as <package> rm -f \
  code_cache/startup_capture/startup_capture.raw \
  code_cache/startup_capture/startup_capture.raw.done
```

A capture is complete only when the `.done` marker exists and `dropped` is `0`.
A missing marker means the process died or the capture was interrupted; the
batches written so far are still convertible, but the profile is truncated.

Do not reinstall while a capture is running: `install` clears the previous raw
file and replaces the configuration, so the next process start uses the new
settings. The agent file itself is installed with a staged rename, so a process
that already mapped the previous copy keeps running; an in-place overwrite would
fault such a process with `SIGBUS`.

Manual install and pull:

```bash
adb shell "run-as <package> sh -c 'mkdir -p code_cache/startup_agents code_cache/startup_capture'"
adb shell "run-as <package> sh -c 'cat > code_cache/startup_agents/liballoc_agent_startup.so'" < liballoc_agent_startup.so
adb shell "printf 'sampleEvery=1\nstackDepth=32\ndurationMs=10000\noutput=startup_capture.raw\nstopFile=startup_capture.stop\n' | run-as <package> sh -c 'cat > code_cache/startup_capture/config'"
adb exec-out run-as <package> cat code_cache/startup_capture/startup_capture.raw > startup_capture.raw
adb exec-out run-as <package> cat code_cache/startup_capture/startup_capture.raw.done > startup_capture.raw.done
```

On the device, the agent file must be readable and executable by the app. The
helper sets mode `0700`.

## Convert to `.asdb`

Build the converter distribution once:

```bash
./gradlew :startup-converter:installDist
```

Then convert the raw file:

```bash
startup-converter/build/install/startup-converter/bin/startup-converter \
  startup_capture.raw startup_capture.asdb
```

The `.done` marker next to the input is read automatically. If it is missing,
the converter still writes the complete batches it can read and warns that the
capture may be incomplete. Pass `--force` to overwrite an existing output file
and `--pid` if the raw file has no process id. Add `--done <path>` to read a
marker from another location.

Open the output with Android Studio's memory profiler (`.asdb`). The file uses
the schema described in [ASDB_FORMAT.md](ASDB_FORMAT.md).

## Raw capture format

All integers are little-endian. The header is:

| Field       | Type | Meaning                                      |
|-------------|------|----------------------------------------------|
| magic       | 8 B  | ASCII `AMPSRAW1`                             |
| version     | u32  | Currently `1`                                |
| sampleEvery | u32  | Record one out of every N allocations        |
| stackDepth  | u32  | Maximum frames per allocation                |
| pid         | u32  | Captured process id                          |
| startTimeNs | u64  | Start timestamp in nanoseconds since epoch   |
| flags       | u32  | Reserved, currently `0`                      |

The header is followed by zero or more records:

```
record: u32 payloadLength, followed by payloadLength bytes
end:    u32 0
```

Each payload is the same native batch format that the on-device `.asdb` writer
consumes: classes, methods, stacks, threads, allocations, and a trailing
cumulative dropped count. See the "Native-to-Kotlin buffer protocol" section of
[ASDB_FORMAT.md](ASDB_FORMAT.md).

The adjacent `.done` marker is a text file:

```
version=1
pid=12345
startNs=1700000000000000000
endNs=1700000015000000000
dropped=0
```

A missing `.done` marker means the process died, was killed, or the agent could
not finish. Records before the truncation are still convertible.

## Limitations

- Only allocations after the framework attaches the startup agent are captured.
  Allocations from the zygote or before process specialization are not included.
- Capturing every allocation is expensive. Prefer `sampleEvery > 1` or a short
  duration.
- The raw capture is process-wide, not per-thread or per-object-graph.
- Every process of a multi-process app shares the same capture configuration and
  output path. Use a separate install for each process, or capture a single
  process; concurrent processes would overwrite each other.
- The `.asdb` schema is reverse-engineered from Android Studio output and is not
  a stable public contract.
- The raw file and `.asdb` file are not compressed. Long captures with
  `sampleEvery = 1` can produce large files.
- Installing the startup agent affects the next process start. The current
  process is not captured.
