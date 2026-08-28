#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
jolt=${JOLT_BIN:-jolt}

cd "$repo"
"$jolt" -Sdeps '{:paths ["src" "resources" "test"]}' \
  build -m otel.viewer-aot-smoke -o target/viewer-aot-smoke
target/viewer-aot-smoke | grep -q 'PASS: viewer assets available'

echo "PASS: viewer self-contained asset smoke"
