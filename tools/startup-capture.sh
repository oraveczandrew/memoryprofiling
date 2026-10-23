#!/bin/sh
#
#    Copyright 2026 András Oravecz <info@oandras.hu>
#
#    Licensed under the Apache License, Version 2.0 (the "License");
#    you may not use this file except in compliance with the License.
#    You may obtain a copy of the License at
#
#        http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.
#
# Installs, stops, pulls and removes the startup capture agent through adb.
# The target application must be debuggable.

set -eu

AGENT_NAME="liballoc_agent_startup.so"
CAPTURE_DIR="code_cache/startup_capture"
AGENTS_DIR="code_cache/startup_agents"
OUTPUT_NAME="startup_capture.raw"
SAMPLE_EVERY=1
STACK_DEPTH=32
DURATION_MS=30000
APK_PATH=""

usage() {
    cat <<USAGE
Usage: $0 <command> <package> [options]

Commands:
  install    Install the agent and configuration for the next launch.
  stop       Ask a running capture to stop and finalize its files.
  pull       Copy the raw capture and .done marker into the current directory.
  uninstall  Remove the installed agent and configuration.

Install options:
  --apk <path>          Read the agent from this APK instead of the installed one.
  --sample-every <n>    Record one out of every n allocations (default: 1).
  --stack-depth <n>     Maximum stored stack frames (default: 32).
  --duration-ms <n>     Recording duration; 0 means until stop (default: 30000).
  --output <name>       Raw capture file name (default: startup_capture.raw).

Global options:
  -s <serial>           Target device serial (default: ANDROID_SERIAL or the only device).
  -h, --help            Show this help.
USAGE
}

fail() {
    echo "startup-capture: $*" >&2
    exit 1
}

adb_cmd() {
    if [ -n "${SERIAL:-}" ]; then
        adb -s "$SERIAL" "$@"
    else
        adb "$@"
    fi
}

run_as() {
    adb_cmd shell "run-as $PACKAGE sh -c '$*'"
}

write_file() {
    destination=$1
    source=$2
    mode=${3:-}
    staged="$destination.new"
    adb_cmd shell "run-as $PACKAGE sh -c 'cat > $staged'" < "$source"
    if [ -n "$mode" ]; then
        run_as "chmod $mode $staged && mv -f $staged $destination"
    else
        run_as "mv -f $staged $destination"
    fi
}

extract_agent() {
    apk=$1
    abi=$(adb_cmd shell getprop ro.product.cpu.abi | tr -d '\r')
    unzip -p "$apk" "lib/$abi/$AGENT_NAME" > "$TEMP_DIR/$AGENT_NAME"
    [ -s "$TEMP_DIR/$AGENT_NAME" ] || fail "$AGENT_NAME is missing from $apk for $abi."
}

COMMAND=${1:-}
[ -n "$COMMAND" ] || { usage; exit 1; }
case "$COMMAND" in
    -h|--help) usage; exit 0 ;;
esac
PACKAGE=${2:-}
[ -n "$PACKAGE" ] || { usage; exit 1; }
shift 2

while [ $# -gt 0 ]; do
    case "$1" in
        --apk) APK_PATH=$2; shift 2 ;;
        --sample-every) SAMPLE_EVERY=$2; shift 2 ;;
        --stack-depth) STACK_DEPTH=$2; shift 2 ;;
        --duration-ms) DURATION_MS=$2; shift 2 ;;
        --output) OUTPUT_NAME=$2; shift 2 ;;
        -s) SERIAL=$2; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown option: $1" ;;
    esac
done

case "$COMMAND" in
    install)
        TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/startup-capture.XXXXXX")
        trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM
        if [ -z "$APK_PATH" ]; then
            APK_PATH=$(adb_cmd shell pm path "$PACKAGE" | sed -n 's/^package://p' | tr -d '\r' | head -n 1)
            [ -n "$APK_PATH" ] || fail "cannot find the installed APK for $PACKAGE."
            adb_cmd pull "$APK_PATH" "$TEMP_DIR/base.apk" >/dev/null
            APK_PATH="$TEMP_DIR/base.apk"
        fi
        extract_agent "$APK_PATH"
        printf 'sampleEvery=%s\nstackDepth=%s\ndurationMs=%s\noutput=%s\nstopFile=startup_capture.stop\n' \
            "$SAMPLE_EVERY" "$STACK_DEPTH" "$DURATION_MS" "$OUTPUT_NAME" \
            > "$TEMP_DIR/config"
        run_as "mkdir -p $AGENTS_DIR $CAPTURE_DIR && rm -f $CAPTURE_DIR/$OUTPUT_NAME $CAPTURE_DIR/$OUTPUT_NAME.done $CAPTURE_DIR/startup_capture.stop"
        write_file "$AGENTS_DIR/$AGENT_NAME" "$TEMP_DIR/$AGENT_NAME" 700
        write_file "$CAPTURE_DIR/config" "$TEMP_DIR/config"
        echo "Installed the startup agent for $PACKAGE."
        echo "Force-stop and launch the app, then run: $0 pull $PACKAGE"
        ;;
    stop)
        run_as "touch $CAPTURE_DIR/startup_capture.stop"
        echo "Requested the startup capture to stop."
        ;;
    pull)
        if ! adb_cmd shell "run-as $PACKAGE test -f $CAPTURE_DIR/$OUTPUT_NAME"; then
            fail "cannot find $CAPTURE_DIR/$OUTPUT_NAME in $PACKAGE."
        fi
        adb_cmd exec-out run-as "$PACKAGE" cat "$CAPTURE_DIR/$OUTPUT_NAME" > "$OUTPUT_NAME"
        [ -s "$OUTPUT_NAME" ] || fail "cannot read $OUTPUT_NAME from $PACKAGE."
        if adb_cmd shell "run-as $PACKAGE test -f $CAPTURE_DIR/$OUTPUT_NAME.done"; then
            adb_cmd exec-out run-as "$PACKAGE" cat "$CAPTURE_DIR/$OUTPUT_NAME.done" > "$OUTPUT_NAME.done"
            echo "Pulled $OUTPUT_NAME and $OUTPUT_NAME.done."
        else
            rm -f "$OUTPUT_NAME.done"
            echo "Pulled $OUTPUT_NAME (no .done marker; the capture may be incomplete)." >&2
        fi
        ;;
    uninstall)
        run_as "rm -f $AGENTS_DIR/$AGENT_NAME $CAPTURE_DIR/config $CAPTURE_DIR/startup_capture.stop"
        echo "Removed the startup agent and configuration for $PACKAGE."
        ;;
    *)
        usage
        exit 1
        ;;
esac
